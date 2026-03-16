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

package client

import "fmt"

// ─── Declarative API ──────────────────────────────────────────────────────────

// DeclarativePayload is the request body for POST /api/declarations/v1/apply.
// Field names mirror the DeclarativePayload Java class in dbaas-aggregator.
type DeclarativePayload struct {
	APIVersion         string          `json:"apiVersion"`
	Kind               string          `json:"kind"`
	SubKind            string          `json:"subKind"`
	DeclarationVersion string          `json:"declarationVersion,omitempty"`
	Metadata           DeclarativeMeta `json:"metadata"`
	Spec               any             `json:"spec"`
}

// DeclarativeMeta is the metadata section of a DeclarativePayload.
type DeclarativeMeta struct {
	Name             string `json:"name"`
	Namespace        string `json:"namespace"`
	MicroserviceName string `json:"microserviceName,omitempty"`
}

// TaskState mirrors the TaskState Java enum in dbaas-aggregator.
// Values are the normalised strings produced by DeclarativeResponse.normalizeStateName().
type TaskState string

const (
	TaskStateNotStarted TaskState = "NOT_STARTED"
	TaskStateInProgress TaskState = "IN_PROGRESS"
	TaskStateCompleted  TaskState = "COMPLETED"
	TaskStateFailed     TaskState = "FAILED"
	TaskStateTerminated TaskState = "TERMINATED"
)

// AggregatorCondition is a single condition entry returned inside DeclarativeResponse.
// Note: the aggregator uses "state" (not the Kubernetes "status") for the condition value.
type AggregatorCondition struct {
	Type    string `json:"type"`
	State   string `json:"state"`
	Reason  string `json:"reason,omitempty"`
	Message string `json:"message,omitempty"`
}

// DeclarativeResponse is returned by:
//   - POST /api/declarations/v1/apply
//   - GET  /api/declarations/v1/operation/{trackingId}/status
//
// When TrackingId is non-empty the operation is asynchronous; the caller must
// poll GetOperationStatus until Status is no longer TaskStateInProgress.
type DeclarativeResponse struct {
	Status     TaskState             `json:"status"`
	TrackingId string                `json:"trackingId,omitempty"`
	Conditions []AggregatorCondition `json:"conditions,omitempty"`
}

// ─── External database registration ──────────────────────────────────────────

// ExternalDatabaseRequest is the request body for
// PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable.
// Field names mirror ExternalDatabaseRequestV3 in dbaas-aggregator.
//
// Each entry in ConnectionProperties is a flat string map assembled by the
// controller from a ConnectionProperty struct plus credentials read from a
// Kubernetes Secret. Every entry must contain a "role" key.
type ExternalDatabaseRequest struct {
	Classifier                 map[string]string   `json:"classifier"`
	Type                       string              `json:"type"`
	DbName                     string              `json:"dbName"`
	ConnectionProperties       []map[string]string `json:"connectionProperties"`
	UpdateConnectionProperties bool                `json:"updateConnectionProperties,omitempty"`
}

// ─── Errors ───────────────────────────────────────────────────────────────────

// AggregatorError represents a non-2xx HTTP response from dbaas-aggregator.
type AggregatorError struct {
	StatusCode int
	Body       string // raw response body (fallback when TMF parse fails)
	TmfMessage string // parsed from TmfErrorResponse.message, if available
}

func (e *AggregatorError) Error() string {
	return fmt.Sprintf("dbaas-aggregator returned HTTP %d: %s", e.StatusCode, e.Body)
}

// UserMessage returns the human-readable error message suitable for Kubernetes
// Events and Condition messages. It returns the parsed TmfErrorResponse.message
// when available, falling back to the raw response body.
func (e *AggregatorError) UserMessage() string {
	if e.TmfMessage != "" {
		return e.TmfMessage
	}
	return e.Body
}

// IsAuthError returns true for HTTP 401 responses.
// A 401 indicates the operator's credentials or role binding are misconfigured;
// it is NOT a spec error, so the controller retries (BackingOff) rather than
// setting InvalidConfiguration.
func (e *AggregatorError) IsAuthError() bool {
	return e.StatusCode == 401
}

// IsClientError returns true for 4xx responses.
// The controller uses this to distinguish a permanent spec error
// (phase → InvalidConfiguration) from a transient server error (phase → BackingOff).
// Note: 401 is handled separately by IsAuthError and maps to BackingOff.
func (e *AggregatorError) IsClientError() bool {
	return e.StatusCode >= 400 && e.StatusCode < 500
}
