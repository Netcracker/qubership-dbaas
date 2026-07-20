/*
Copyright 2026.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

// Package tracing wires the operator into the platform OpenTelemetry tracing
// contract: OTLP http/protobuf export via TRACING_HOST, b3multi propagation,
// and parentbased_traceidratio sampling driven by TRACING_SAMPLER_PROBABILISTIC.
//
// The operator has no inbound HTTP request to auto-instrument (all "requests"
// are Kubernetes reconcile loops), so StartSpan is called once per reconcile
// from the same choke point that already seeds the request ID
// (internal/controller/helpers.go initReconcileContext), giving every
// reconcile a root span without spreading OTel setup across each controller.
package tracing

import (
	"context"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"

	"go.opentelemetry.io/contrib/propagators/b3"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

const instrumentationName = "github.com/netcracker/qubership-dbaas/dbaas-operator"

// traceIDLogKey and spanIDLogKey are read back by the platform logger via
// logging.DefaultFormat.SetCustomLogFields("...%{traceId}...%{spanId}...") —
// see cmd/main.go and the logging package's GetValueOrPlaceholder, which reads
// ctx.Value with the placeholder name as a plain string key. Must stay plain
// strings (not a private key type) to match that lookup.
const (
	traceIDLogKey = "traceId"
	spanIDLogKey  = "spanId"
)

var tracer = otel.Tracer(instrumentationName)

// Init wires the global TracerProvider and propagator from the platform
// TRACING_* env contract. When TRACING_ENABLED is not "true" it leaves the
// no-op global provider in place and returns a no-op shutdown func.
func Init(ctx context.Context, serviceName string) (shutdown func(context.Context) error, err error) {
	if !strings.EqualFold(os.Getenv("TRACING_ENABLED"), "true") {
		return func(context.Context) error { return nil }, nil
	}

	host := os.Getenv("TRACING_HOST")
	if host == "" {
		host = "nc-diagnostic-agent"
	}

	ratio := 0.01
	if v := os.Getenv("TRACING_SAMPLER_PROBABILISTIC"); v != "" {
		if f, perr := strconv.ParseFloat(v, 64); perr == nil {
			ratio = f
		}
	}

	exp, err := otlptracehttp.New(ctx,
		// host:port only, no scheme - see code-migration.md OTLP HTTP exporter wiring.
		otlptracehttp.WithEndpoint(net.JoinHostPort(host, "4318")),
		otlptracehttp.WithURLPath("/v1/traces"),
		// The platform tracing proxy is in-cluster and speaks plain HTTP.
		otlptracehttp.WithInsecure(),
	)
	if err != nil {
		return nil, fmt.Errorf("create OTLP exporter: %w", err)
	}

	res, err := resource.New(ctx, resource.WithAttributes(
		semconv.ServiceName(serviceName),
	))
	if err != nil {
		return nil, fmt.Errorf("build OTel resource: %w", err)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(ratio))),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(b3.New()))
	tracer = tp.Tracer(instrumentationName)

	return tp.Shutdown, nil
}

// StartSpan starts a span named name (a child of any span already in ctx, or a
// new root otherwise) and, when tracing is enabled, stashes its trace/span IDs
// as plain context values so the platform logger's custom log fields pick them
// up in every log line written with this ctx.
func StartSpan(ctx context.Context, name string) (context.Context, trace.Span) {
	ctx, span := tracer.Start(ctx, name)
	if sc := span.SpanContext(); sc.IsValid() {
		// Plain string keys are required here, not a private key type: logging.
		// GetValueOrPlaceholder (called by the platform logger for %{traceId}/
		// %{spanId}) does ctx.Value(name) with name taken verbatim from the
		// format string, so the stored key must be the identical string value.
		ctx = context.WithValue(ctx, traceIDLogKey, sc.TraceID().String()) //nolint:staticcheck // see comment above
		ctx = context.WithValue(ctx, spanIDLogKey, sc.SpanID().String())   //nolint:staticcheck // see comment above
	}
	return ctx, span
}
