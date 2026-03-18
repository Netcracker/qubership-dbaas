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

package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

// Phase represents the processing phase of a dbaas operator resource.
// The controller drives resources through a state machine:
//
//	Unknown → Updating → WaitingForDependency → Updated
//	                   ↘ BackingOff (transient error, will retry)
//	                   ↘ InvalidConfiguration (permanent error, no retry)
//
// +kubebuilder:validation:Enum=Unknown;Updating;WaitingForDependency;Updated;BackingOff;InvalidConfiguration
type Phase string

const (
	// PhaseUnknown is the initial phase assigned to a newly created resource.
	// The controller will immediately transition to Updating.
	PhaseUnknown Phase = "Unknown"

	// PhaseUpdating indicates the controller is actively sending the resource
	// to the dbaas-aggregator POST /api/declarations/v1/apply endpoint.
	PhaseUpdating Phase = "Updating"

	// PhaseWaitingForDependency indicates an asynchronous database provisioning
	// operation has been started and the controller is polling its status via
	// GET /api/declarations/v1/operation/{trackingId}/status.
	PhaseWaitingForDependency Phase = "WaitingForDependency"

	// PhaseUpdated indicates the resource was successfully processed by dbaas-aggregator.
	PhaseUpdated Phase = "Updated"

	// PhaseBackingOff indicates a transient error occurred. The controller will
	// retry with exponential back-off.
	PhaseBackingOff Phase = "BackingOff"

	// PhaseInvalidConfiguration indicates a permanent validation error. The resource
	// will not be re-processed until the spec is changed.
	PhaseInvalidConfiguration Phase = "InvalidConfiguration"
)

// OperatorStatus contains common status fields shared by all dbaas operator resources.
// Both DatabaseDeclaration and DbPolicy embed this struct inline.
type OperatorStatus struct {
	// phase represents the current processing phase of the resource.
	// +kubebuilder:default=Unknown
	// +optional
	Phase Phase `json:"phase,omitempty"`

	// trackingId is the identifier returned by dbaas-aggregator when a database
	// provisioning operation is started asynchronously (HTTP 202). The controller
	// stores it here and uses it to poll the operation status on subsequent reconciles.
	// Cleared once the operation reaches a terminal state (Completed or Failed).
	// +optional
	TrackingId string `json:"trackingId,omitempty"`

	// observedGeneration reflects the .metadata.generation that was last processed
	// by the controller. When the current generation differs from this value, the
	// controller clears all conditions and starts a fresh reconciliation cycle.
	// Zero (or less than metadata.generation) means the current generation has not
	// yet been fully reconciled — for example, the controller is in BackingOff
	// waiting for a transient error to clear.
	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// conditions represent the current state of the resource.
	// Condition types used by ExternalDatabaseDeclaration:
	//   - "Ready"   — True when the database is successfully registered with
	//                 dbaas-aggregator for the current generation.
	//                 False on any error; see Reason for the category.
	//   - "Stalled" — True when the error is permanent and the controller will
	//                 not retry until the spec is changed (e.g. InvalidSpec,
	//                 AggregatorRejected). False for transient errors that are
	//                 retried automatically (e.g. SecretError, AggregatorError).
	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}
