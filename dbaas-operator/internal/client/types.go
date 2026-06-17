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

import (
	"fmt"
	"net/http"
	"time"
)

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
// When TrackingID is non-empty the operation is asynchronous; the caller must
// poll GetOperationStatus until Status is no longer TaskStateInProgress.
type DeclarativeResponse struct {
	Status     TaskState             `json:"status"`
	TrackingID string                `json:"trackingId,omitempty"`
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
	// Classifier is sent to dbaas-aggregator as the request body's "classifier"
	// field. The aggregator declares it as SortedMap<String, Object> in
	// ExternalDatabaseRequestV3.java — values may be any JSON type
	// (string, number, boolean, nested object, array). Use map[string]any
	// rather than map[string]string to preserve the wire-side fidelity.
	Classifier                 map[string]any      `json:"classifier"`
	Type                       string              `json:"type"`
	DbName                     string              `json:"dbName"`
	ConnectionProperties       []map[string]string `json:"connectionProperties"`
	UpdateConnectionProperties bool                `json:"updateConnectionProperties,omitempty"`
}

// OnMicroserviceRuleRequest is the request item for
// PUT /api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices.
type OnMicroserviceRuleRequest struct {
	Type          string               `json:"type"`
	Rules         []RuleOnMicroservice `json:"rules"`
	Microservices []string             `json:"microservices"`
}

// RuleOnMicroservice mirrors dbaas-aggregator's RuleOnMicroservice DTO.
type RuleOnMicroservice struct {
	Label string `json:"label"`
}

// NamespaceBalancingRuleRequest is the request body for
// PUT /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}.
type NamespaceBalancingRuleRequest struct {
	Order *int64                     `json:"order,omitempty"`
	Type  string                     `json:"type"`
	Rule  NamespaceBalancingRuleBody `json:"rule"`
}

// NamespaceBalancingRuleBody mirrors dbaas-aggregator's RuleBody DTO.
type NamespaceBalancingRuleBody struct {
	Type   string         `json:"type"`
	Config map[string]any `json:"config"`
}

// PermanentBalancingRuleRequest is the request item for
// PUT /api/v3/dbaas/balancing/rules/permanent.
type PermanentBalancingRuleRequest struct {
	DbType             string   `json:"dbType"`
	PhysicalDatabaseID string   `json:"physicalDatabaseId"`
	Namespaces         []string `json:"namespaces"`
}

// PermanentBalancingRuleDeleteRequest is the request item for
// DELETE /api/v3/dbaas/balancing/rules/permanent.
type PermanentBalancingRuleDeleteRequest struct {
	DbType     string   `json:"dbType,omitempty"`
	Namespaces []string `json:"namespaces"`
}

// ─── InternalDatabase wire types ──────────────────────────────────────────

// DatabaseDeclarationSpecWire is the wire representation of the spec field
// in POST /api/declarations/v1/apply for subKind=DatabaseDeclaration.
// Field names mirror com.netcracker.cloud.dbaas.dto.declarative.DatabaseDeclaration
// in dbaas-aggregator.
type DatabaseDeclarationSpecWire struct {
	// ClassifierConfig wraps the classifier flat map.
	// Mirrors InternalDatabase.ClassifierConfig (static nested class).
	ClassifierConfig     ClassifierConfigWire      `json:"classifierConfig"`
	Type                 string                    `json:"type"`
	Lazy                 bool                      `json:"lazy,omitempty"`
	Settings             map[string]string         `json:"settings,omitempty"`
	NamePrefix           string                    `json:"namePrefix,omitempty"`
	VersioningConfig     *VersioningConfigWire     `json:"versioningConfig,omitempty"`
	InitialInstantiation *InitialInstantiationWire `json:"initialInstantiation,omitempty"`
}

// ClassifierConfigWire mirrors InternalDatabase.ClassifierConfig in dbaas-aggregator:
//
//	public static class ClassifierConfig {
//	    @JsonProperty("classifier")
//	    private SortedMap<String, Object> classifier;
//	}
//
// The classifier fields (microserviceName, scope, namespace, tenantId, customKeys, …)
// are flattened into the map. customKeys itself is a nested map[string]any inside it.
type ClassifierConfigWire struct {
	Classifier map[string]any `json:"classifier"`
}

// VersioningConfigWire mirrors InternalDatabase.VersioningConfig in dbaas-aggregator.
type VersioningConfigWire struct {
	Approach string `json:"approach,omitempty"`
}

// InitialInstantiationWire mirrors InternalDatabase.InitialInstantiation in dbaas-aggregator:
//
//	public static class InitialInstantiation {
//	    @JsonProperty("approach")         String approach;
//	    @JsonProperty("sourceClassifier") SortedMap<String, Object> sourceClassifier;
//	}
//
// sourceClassifier is a flat map with the same shape as ClassifierConfigWire.Classifier.
type InitialInstantiationWire struct {
	Approach         string         `json:"approach,omitempty"`
	SourceClassifier map[string]any `json:"sourceClassifier,omitempty"`
}

// ─── get-by-classifier ────────────────────────────────────────────────────────

// GetByClassifierRequest is the body for
// POST /api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}.
type GetByClassifierRequest struct {
	Classifier    map[string]any `json:"classifier"`
	OriginService string         `json:"originService,omitempty"`
	UserRole      string         `json:"userRole,omitempty"`
}

// DatabaseResponseSingleCP is the response from GetDatabaseByClassifier.
// ConnectionProperties keys are dynamic (host, port, username, password, name, url, role, roHost, …).
//
// The aggregator returns the full DatabaseResponseV3SingleCP descriptor (id,
// name, namespace, type, settings, connectionProperties); these fields are
// surfaced so the operator can mirror them into the Secret's metadata.json for
// dbaas-client. Note: the aggregator documents Id as best-effort on a
// by-classifier lookup ("might not be used … for security purpose"), so callers
// must tolerate an empty Id.
type DatabaseResponseSingleCP struct {
	Id                   string         `json:"id,omitempty"`
	Name                 string         `json:"name,omitempty"`
	Namespace            string         `json:"namespace,omitempty"`
	Type                 string         `json:"type,omitempty"`
	Settings             map[string]any `json:"settings,omitempty"`
	ConnectionProperties map[string]any `json:"connectionProperties,omitempty"`
}

// ─── changed-databases (rotation pull) ──────────────────────────────────────────

// ChangedDatabaseRef identifies a database whose credentials changed (password
// rotation or restore), as returned by GET /api/v3/dbaas/databases/changed. It
// carries only the identity needed to locate the consuming DatabaseSecretClaim
// CR(s); connection properties are fetched separately via GetDatabaseByClassifier.
// Id together with LastRotatedAt forms the keyset cursor the poller advances by.
type ChangedDatabaseRef struct {
	Id            string         `json:"id"`
	Namespace     string         `json:"namespace"`
	Classifier    map[string]any `json:"classifier"`
	Type          string         `json:"type"`
	LastRotatedAt time.Time      `json:"lastRotatedAt"`
}

// ChangeCursor is the keyset cursor (lastRotatedAt, id) for the changed-databases
// feed. The composite key makes paging deterministic even when many rows share an
// identical lastRotatedAt (e.g. a restore stamps one timestamp across a database's
// registries), so the poller always makes forward progress.
type ChangeCursor struct {
	LastRotatedAt time.Time `json:"lastRotatedAt"`
	Id            string    `json:"id"`
}

// ChangedDatabasesResponse is the response from GetChangedSince. HighWaterMark is
// the latest (lastRotatedAt, id) currently known across all databases; it seeds
// the poll cursor on the first (since-less) call and is nil when nothing has
// rotated yet. On subsequent calls the cursor is advanced from the returned Items,
// not from HighWaterMark, so a full page does not skip its tail.
type ChangedDatabasesResponse struct {
	Items         []ChangedDatabaseRef `json:"items"`
	HighWaterMark *ChangeCursor        `json:"highWaterMark"`
}

// ─── Errors ───────────────────────────────────────────────────────────────────

// AggregatorError represents a non-2xx HTTP response from dbaas-aggregator.
type AggregatorError struct {
	StatusCode int
	Body       string // raw response body (fallback when TMF parse fails)
	TmfCode    string // parsed from TmfErrorResponse.code
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
	return e.StatusCode == http.StatusUnauthorized
}

// IsSpecRejection returns true when the aggregator explicitly rejected the request
// content — codes where retrying the same payload will not succeed:
//   - 400 Bad Request    — validation failure (CORE-DBAAS-4035/4036)
//   - 403 Forbidden      — namespace/policy violation (CORE-DBAAS-4004)
//   - 409 Conflict       — resource already exists (CORE-DBAAS-4002)
//   - 410 Gone           — resource permanently removed
//   - 422 Unprocessable  — semantic validation failure
//
// Infrastructure and proxy 4xx codes (404 Not Found, 405 Method Not Allowed,
// 408 Request Timeout, 429 Too Many Requests, etc.) are NOT spec rejections.
// They are transient and the controller should back-off and retry.
// 401 is handled separately by IsAuthError.
func (e *AggregatorError) IsSpecRejection() bool {
	switch e.StatusCode {
	case http.StatusBadRequest, http.StatusForbidden, http.StatusConflict, http.StatusGone, http.StatusUnprocessableEntity:
		return true
	}
	return false
}

// IsDatabaseNotFound returns true only for a 404 that carries TMF error code
// CORE-DBAAS-4006 (database not yet registered). A bare 404 without a TMF body
// (blue-green edge case: no active namespace in the domain) returns false and is
// treated as a generic transient AggregatorError.
func (e *AggregatorError) IsDatabaseNotFound() bool {
	return e.StatusCode == http.StatusNotFound && e.TmfCode == "CORE-DBAAS-4006"
}
