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

package v1

import (
	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// ObservedGenerationSetter is implemented by CR root types whose status embeds
// OperatorStatus and therefore can persist the latest reconciled generation.
//
// +kubebuilder:object:generate=false
type ObservedGenerationSetter interface {
	SetObservedGeneration(int64)
}

// Classifier uniquely identifies a database in dbaas-aggregator.
// All keys are sorted alphabetically by the aggregator for identity comparison.
//
// Shared by dbaas operator resources that reference a database by its
// dbaas-aggregator identity.
type Classifier struct {
	// microserviceName is the name of the microservice that owns the database.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	MicroserviceName string `json:"microserviceName"`

	// scope defines the logical scope of the database, e.g. "service" or "tenant".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Scope string `json:"scope"`

	// namespace is the Kubernetes namespace of the owning service.
	// If omitted, the aggregator uses metadata.namespace from the request.
	// If set, it must equal the CR's metadata.namespace — a mismatch causes
	// InvalidConfiguration at the controller level.
	// +optional
	Namespace string `json:"namespace,omitempty"`

	// tenantId is the tenant identifier for multi-tenant deployments.
	// Only relevant when scope="tenant".
	// +optional
	TenantID string `json:"tenantId,omitempty"`

	// customKeys is an optional nested map for adapter-specific or
	// application-specific identifiers (e.g. logicalDBName).
	// Values can be any valid JSON type (string, number, boolean,
	// nested object, array). Not validated by the aggregator — passed
	// through as-is.
	// +optional
	CustomKeys map[string]apiextensionsv1.JSON `json:"customKeys,omitempty"`

	// extraKeys are arbitrary additional classifier identity fields flattened
	// onto the TOP level of the wire classifier, alongside microserviceName/
	// scope/namespace/tenantId (unlike customKeys, which is nested under
	// "customKeys"). They exist for compatibility with the legacy open
	// classifier model, where services could place arbitrary identity fields
	// at the classifier's top level. Values may be any JSON type (string,
	// number, boolean, nested object, array). The reserved keys
	// microserviceName, scope, namespace, tenantId and customKeys are not
	// allowed: the controller rejects such a spec with InvalidConfiguration, and
	// ClassifierFlatMap also skips them defensively so the typed fields always
	// win. (A CEL rule cannot guard this map because its values are unstructured
	// JSON, so the check lives in the controller.)
	// Because these fields are part of the database identity, every consumer's
	// dbaas-client must produce the same keys/values, otherwise the database
	// (and its mounted Secret) will not be found.
	// +optional
	ExtraKeys map[string]apiextensionsv1.JSON `json:"extraKeys,omitempty"`
}

// Phase represents the processing phase of a dbaas operator resource.
// The controller drives resources through a state machine:
//
//	Unknown → Processing → Succeeded
//	                     ↘ BackingOff (transient error, will retry)
//	                     ↘ InvalidConfiguration (permanent error, no retry)
//
// InternalDatabase additionally uses WaitingForDependency while polling
// an asynchronous provisioning operation in dbaas-aggregator.
// ExternalDatabase and DatabaseAccessPolicy never transition into WaitingForDependency —
// their reconcile flows are fully synchronous.
//
// Phase is an observational summary for humans, not an API contract: it exists so
// that `kubectl get` can show a single readable column, which conditions cannot
// provide (JSONPath selects, it cannot branch). Conditions are the source of
// truth — automation must read status.conditions, never status.phase.
//
// Deliberately not constrained by a CEL/OpenAPI enum. A closed enum on a status
// field means that shipping a new phase value before the updated CRD reaches the
// cluster makes the API server reject the whole status write — which would drop
// the conditions in the same request and leave the resource unobservable.
type Phase string

const (
	// PhaseUnknown is the initial phase assigned to a newly created resource.
	// The controller will immediately transition to Processing.
	PhaseUnknown Phase = "Unknown"

	// PhaseProcessing indicates the controller is actively registering the
	// resource with dbaas-aggregator.
	PhaseProcessing Phase = "Processing"

	// PhaseWaitingForDependency indicates the controller is polling an
	// asynchronous provisioning operation in dbaas-aggregator (HTTP 202 +
	// trackingId flow). Used only by InternalDatabase — ExternalDatabase
	// and DatabaseAccessPolicy have synchronous reconcile flows and never use this phase.
	PhaseWaitingForDependency Phase = "WaitingForDependency"

	// PhaseSucceeded indicates the resource was successfully processed by dbaas-aggregator.
	PhaseSucceeded Phase = "Succeeded"

	// PhaseBackingOff indicates a transient error occurred. The controller will
	// retry with exponential back-off.
	PhaseBackingOff Phase = "BackingOff"

	// PhaseInvalidConfiguration indicates a permanent validation error. The resource
	// will not be re-processed until the spec is changed.
	PhaseInvalidConfiguration Phase = "InvalidConfiguration"
)

// OperatorStatus contains common status fields shared by all dbaas operator resources.
type OperatorStatus struct {
	// phase is a human-readable summary of the conditions below, provided so that
	// `kubectl get` can show one column. Do not automate against it — read
	// conditions instead. Not defaulted by the API server: status is owned by the
	// controller, which always sets phase alongside the conditions.
	// +optional
	Phase Phase `json:"phase,omitempty"`

	// observedGeneration reflects the .metadata.generation that was last processed
	// by the controller. When the current generation differs from this value, the
	// controller clears all conditions and starts a fresh reconciliation cycle.
	// Zero (or less than metadata.generation) means the current generation has not
	// yet been fully reconciled — for example, the controller is in BackingOff
	// waiting for a transient error to clear.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// conditions represent the current state of the resource.
	// Condition types used by all dbaas operator resources (ExternalDatabase, DatabaseAccessPolicy, InternalDatabase):
	//   - "Ready"   — True when the resource was successfully processed by
	//                 dbaas-aggregator for the current generation.
	//                 ExternalDatabase: reason "DatabaseRegistered" on success.
	//                 DatabaseAccessPolicy: reason "PolicyApplied" on success.
	//                 InternalDatabase: reason "DatabaseProvisioned" on success;
	//                   reason "ProvisioningStarted" while the async operation is in progress.
	//                 False on any error; see Reason for the error category.
	//   - "Stalled" — True when the error is permanent and the controller will
	//                 not retry until the spec is changed (e.g. InvalidSpec,
	//                 AggregatorRejected). False for transient errors that are
	//                 retried automatically (e.g. SecretError, AggregatorError,
	//                 Unauthorized, ProvisioningStarted).
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`

	// lastRequestId is the X-Request-Id of the most recent reconcile attempt.
	// Use this value to correlate operator logs with dbaas-aggregator logs when
	// investigating issues for a specific resource.
	// +optional
	LastRequestID string `json:"lastRequestId,omitempty"`
}
