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
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// DatabaseSecretClaimSpec defines the desired state of DatabaseSecretClaim.
type DatabaseSecretClaimSpec struct {
	// classifier uniquely identifies the database whose credentials this secret tracks.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="classifier is immutable after creation"
	Classifier Classifier `json:"classifier"`

	// type is the database engine type, e.g. "postgresql", "mongodb".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="type is immutable after creation"
	Type string `json:"type"`

	// userRole is the role/permission level for the generated credentials.
	// +optional
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="spec.userRole is immutable after creation"
	UserRole string `json:"userRole,omitempty"`

	// secretName is the name of the Kubernetes Secret to create or update with credentials.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="secretName is immutable after creation"
	SecretName string `json:"secretName"`
}

// DatabaseSecretClaimStatus defines the observed state of DatabaseSecretClaim.
type DatabaseSecretClaimStatus struct {
	OperatorStatus `json:",inline"`

	// firstNotFoundAt records the first time dbaas-aggregator returned
	// DatabaseNotFound (HTTP 404 + CORE-DBAAS-4006) for this CR's classifier.
	// It is set on the first 404 and cleared on any successful aggregator
	// response. The controller uses it to detect a CR that has been waiting
	// too long for its database to appear (e.g. a typo in spec.classifier)
	// and, after a fixed timeout, switches the Ready condition's reason to
	// DatabaseNotFoundTimeout and stops emitting per-cycle Warning events.
	// Polling continues so the CR can still self-heal if the database
	// eventually appears.
	// +optional
	FirstNotFoundAt *metav1.Time `json:"firstNotFoundAt,omitempty"`

	// lastRotatedAt records the time of the most recent connection properties
	// change applied to the target Secret. Updated only when Reconcile actually
	// changes the Secret bytes; no-op reconciles do not touch it. The controller
	// sets this both when a rotation event from dbaas-aggregator triggers the
	// change and when a safety-net poll detects drift in the aggregator's data.
	// Distinct from metadata.creationTimestamp (initial Secret creation does not
	// advance this field — see the SecretCreated vs SecretRotated event reasons).
	// +optional
	LastRotatedAt *metav1.Time `json:"lastRotatedAt,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=databasesecretclaims,singular=databasesecretclaim,shortName=dbdsc,categories=dbaas
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".spec.type"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DatabaseSecretClaim is the Schema for the databasesecretclaims API (dbaas.netcracker.com group).
// The label app.kubernetes.io/name is required — its value is sent as originService in the
// get-by-classifier request to dbaas-aggregator. It cannot be enforced by CEL: a root-level
// XValidation rule only sees metadata.name and metadata.generateName, so referencing
// self.metadata.labels makes the API server reject the whole CRD with "undefined field 'labels'"
// (controller-gen itself generates such a rule without complaint). The same limit rules out a CEL
// check that spec.classifier.namespace equals metadata.namespace. Both are therefore enforced by a
// controller-level pre-flight check before the aggregator is called.
// It requests dbaas-aggregator to provision credentials for a managed database
// and write them into a named Kubernetes Secret in the same namespace.
type DatabaseSecretClaim struct {
	metav1.TypeMeta `json:",inline"`

	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec DatabaseSecretClaimSpec `json:"spec"`

	// +optional
	Status DatabaseSecretClaimStatus `json:"status,omitempty"`
}

func (s *DatabaseSecretClaim) SetObservedGeneration(generation int64) {
	s.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DatabaseSecretClaimList contains a list of DatabaseSecretClaim.
type DatabaseSecretClaimList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DatabaseSecretClaim `json:"items"`
}

func init() {
	SchemeBuilder.Register(&DatabaseSecretClaim{}, &DatabaseSecretClaimList{})
}
