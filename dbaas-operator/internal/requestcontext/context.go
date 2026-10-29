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

// Package requestcontext centralizes the operator's X-Request-Id lifecycle.
package requestcontext

import (
	"context"
	"fmt"

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
)

// RegisterProviders registers every context provider required by the operator.
// It must run during process startup, before any context is initialized or read.
func RegisterProviders() {
	ctxmanager.Register([]ctxmanager.ContextProvider{xrequestid.XRequestIdProvider{}})
}

// WithFreshRequestID creates a new correlation scope and returns both the
// enriched context and the generated ID used by status and event reporting.
func WithFreshRequestID(ctx context.Context) (context.Context, string) {
	id := uuid.New().String()
	return ctxmanager.InitContext(ctx, map[string]any{
		xrequestid.X_REQUEST_ID_HEADER_NAME: id,
	}), id
}

// RequestID returns the initialized request ID with an actionable error that
// distinguishes missing startup registration from a caller that omitted setup.
func RequestID(ctx context.Context) (string, error) {
	if _, err := ctxmanager.GetProvider(xrequestid.X_REQUEST_ID_COTEXT_NAME); err != nil {
		return "", fmt.Errorf("request ID provider is not registered; register context providers during process startup: %w", err)
	}

	requestID, err := xrequestid.Of(ctx)
	if err != nil {
		return "", fmt.Errorf("request ID context is not initialized; call WithFreshRequestID before invoking the aggregator: %w", err)
	}
	if requestID.GetRequestId() == "" {
		return "", fmt.Errorf("request ID context contains an empty %s value; initialize a fresh request context before invoking the aggregator",
			xrequestid.X_REQUEST_ID_HEADER_NAME)
	}
	return requestID.GetRequestId(), nil
}
