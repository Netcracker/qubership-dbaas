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
	APIVersion string         `json:"apiVersion"`
	Kind       string         `json:"kind"`
	SubKind    string         `json:"subKind"`
	Metadata   DeclarativeMeta `json:"metadata"`
	Spec       any            `json:"spec"`
}

// DeclarativeMeta is the metadata section of a DeclarativePayload.
type DeclarativeMeta struct {
	Name             string `json:"name"`
	Namespace        string `json:"namespace"`
	MicroserviceName string `json:"microserviceName,omitempty"`
}

// TaskState mirrors the TaskState Java enum in dbaas-aggregator.
type TaskState string

const (
	TaskStateInProgress TaskState = "IN_PROGRESS"
	TaskStateCompleted  TaskState = "COMPLETED"
	TaskStateFailed     TaskState = "FAILED"
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
	Body       string
}

func (e *AggregatorError) Error() string {
	return fmt.Sprintf("dbaas-aggregator returned HTTP %d: %s", e.StatusCode, e.Body)
}

// IsClientError returns true for 4xx responses.
// The controller uses this to distinguish a permanent configuration error
// (phase → InvalidConfiguration) from a transient server error (phase → BackingOff).
func (e *AggregatorError) IsClientError() bool {
	return e.StatusCode >= 400 && e.StatusCode < 500
}
