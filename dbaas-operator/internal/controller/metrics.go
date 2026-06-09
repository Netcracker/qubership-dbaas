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

package controller

import (
	"errors"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"sigs.k8s.io/controller-runtime/pkg/metrics"
)

// Label value constants.

// Keeping the constants here because they are only relevant to Prometheus metrics.
const (
	controllerEDB = "externaldatabase"
	controllerDD  = "internaldatabase"
	controllerDP  = "dbpolicy"

	triggerSpecChange             = "spec_change"
	triggerSecretChange           = "secret_change"
	triggerNamespaceBindingChange = "namespace_binding_change"
	triggerPolling                = "polling"

	resultSuccess       = "success"
	resultAuthError     = "auth_error"
	resultSpecRejection = "spec_rejection"
	resultServerError   = "server_error"
	resultNetworkError  = "network_error"

	asyncResultFailed     = "failed"
	asyncResultTerminated = "terminated"

	secretReasonNotFound   = "secret_not_found"
	secretReasonKeyMissing = "key_missing"
	secretReasonKeyEmpty   = "key_empty"
	secretReasonForbidden  = "forbidden"
	secretReasonReadFailed = "secret_read_failed"

	operationRegisterEDB = "register_external_database"
	operationApplyConfig = "apply_config"
	operationPollStatus  = "poll_status"
)

// Metric declarations.

// dbaasReconcileTriggerTotal counts reconcile invocations by meaningful source.
// Controller-runtime already exposes reconcile totals; this metric adds the
// trigger dimension that the framework cannot infer.
var dbaasReconcileTriggerTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "dbaas_reconcile_trigger_total",
		Help: "Total reconcile invocations by trigger source.",
	},
	[]string{"controller", "trigger"},
)

// dbaasSecretResolutionErrorsTotal counts failures reading credential Secrets,
// labelled by error category. A non-zero value means a credential rotation
// left a database without valid credentials - direct service impact.
var dbaasSecretResolutionErrorsTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "dbaas_secret_resolution_errors_total",
		Help: "Failures reading credential Secrets referenced by ExternalDatabase, scoped to namespaces owned by this operator instance. Labelled by namespace and failure category (secret_not_found, key_missing, key_empty, forbidden, secret_read_failed).",
	},
	[]string{"namespace", "reason"},
)

// dbaasSecretRotationPropagationSeconds measures end-to-end time from a
// Secret change triggering a reconcile to the ExternalDatabase reaching
// Succeeded. This is the SLO metric for the credential-rotation feature.
var dbaasSecretRotationPropagationSeconds = prometheus.NewHistogram(
	prometheus.HistogramOpts{
		Name:    "dbaas_secret_rotation_propagation_seconds",
		Help:    "Time from a Secret change trigger to ExternalDatabase reaching Succeeded.",
		Buckets: []float64{0.5, 1, 2, 5, 10, 30, 60, 120, 300, 600},
	},
)

// dbaasAggregatorRequestDurationSeconds tracks HTTP call latency to dbaas-aggregator.
// P50/P90/P99 per operation type feed latency SLO alerting.
var dbaasAggregatorRequestDurationSeconds = prometheus.NewHistogramVec(
	prometheus.HistogramOpts{
		Name:    "dbaas_aggregator_request_duration_seconds",
		Help:    "Duration of HTTP calls to dbaas-aggregator by controller and operation.",
		Buckets: []float64{0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30},
	},
	[]string{"controller", "operation"},
)

// dbaasAggregatorRequestsTotal counts aggregator calls by controller, operation,
// and result. The result label distinguishes user errors (spec_rejection) from
// platform errors (auth_error, server_error) so each can be routed to the
// correct owner.
var dbaasAggregatorRequestsTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "dbaas_aggregator_requests_total",
		Help: "Total calls to dbaas-aggregator, labelled by controller, operation, and result.",
	},
	[]string{"controller", "operation", "result"},
)

// dbaasAsyncOperationDurationSeconds measures end-to-end provisioning time from
// async submit (HTTP 202) to final poll outcome. This is the user-visible DD SLO.
var dbaasAsyncOperationDurationSeconds = prometheus.NewHistogramVec(
	prometheus.HistogramOpts{
		Name:    "dbaas_async_operation_duration_seconds",
		Help:    "End-to-end duration of async InternalDatabase provisioning operations.",
		Buckets: []float64{1, 5, 10, 30, 60, 300, 600, 1800, 3600, 7200},
	},
	[]string{"result"},
)

func init() {
	metrics.Registry.MustRegister(
		dbaasReconcileTriggerTotal,
		dbaasSecretResolutionErrorsTotal,
		dbaasSecretRotationPropagationSeconds,
		dbaasAggregatorRequestDurationSeconds,
		dbaasAggregatorRequestsTotal,
		dbaasAsyncOperationDurationSeconds,
	)
}

// Helper functions.

// recordAggregatorCall records both the duration and the outcome counter for a
// single aggregator HTTP call. Call it immediately after every aggregator
// method returns, passing the start time and the error (nil = success).
func recordAggregatorCall(controller, operation string, start time.Time, err error) {
	dbaasAggregatorRequestDurationSeconds.
		WithLabelValues(controller, operation).
		Observe(time.Since(start).Seconds())
	dbaasAggregatorRequestsTotal.
		WithLabelValues(controller, operation, aggregatorResult(err)).
		Inc()
}

// aggregatorResult maps an error from AggregatorClient to a result label.
func aggregatorResult(err error) string {
	if err == nil {
		return resultSuccess
	}
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) {
		if aggErr.IsAuthError() {
			return resultAuthError
		}
		if aggErr.IsSpecRejection() {
			return resultSpecRejection
		}
		return resultServerError
	}
	return resultNetworkError
}

type secretResolutionError struct {
	reason string
	err    error
}

func (e *secretResolutionError) Error() string {
	return e.err.Error()
}

func (e *secretResolutionError) Unwrap() error {
	return e.err
}

func secretResolutionReason(err error) string {
	var secretErr *secretResolutionError
	if errors.As(err, &secretErr) {
		return secretErr.reason
	}
	return secretReasonReadFailed
}

func recordReconcileTrigger(controller, trigger string) {
	dbaasReconcileTriggerTotal.WithLabelValues(controller, trigger).Inc()
}
