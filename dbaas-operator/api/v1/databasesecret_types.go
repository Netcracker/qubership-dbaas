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

// DatabaseSecretSpec defines the desired state of DatabaseSecret.
type DatabaseSecretSpec struct {
	// classifier uniquely identifies the database whose credentials this secret tracks.
	// +kubebuilder:validation:Required
	Classifier Classifier `json:"classifier"`

	// type is the database engine type, e.g. "postgresql", "mongodb".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// userRole is the role/permission level for the generated credentials.
	// +optional
	UserRole string `json:"userRole,omitempty"`

	// secretName is the name of the Kubernetes Secret to create or update with credentials.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	SecretName string `json:"secretName"`
}

// DatabaseSecretStatus defines the observed state of DatabaseSecret.
type DatabaseSecretStatus struct {
	OperatorStatus `json:",inline"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=databasesecrets,singular=databasesecret
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".spec.type"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DatabaseSecret is the Schema for the databasesecrets API (dbaas.netcracker.com group).
// The label app.kubernetes.io/name is required — its value is sent as originService in the
// get-by-classifier request to dbaas-aggregator. Absence of the label results in an empty
// originService, which causes the aggregator to return HTTP 400 (CORE-DBAAS-4022) →
// InvalidConfiguration phase. CEL validation of metadata.labels at root schema level is not
// supported by controller-gen; enforcement is done through the aggregator error flow.
// It requests dbaas-aggregator to provision credentials for a managed database
// and write them into a named Kubernetes Secret in the same namespace.
type DatabaseSecret struct {
	metav1.TypeMeta `json:",inline"`

	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec DatabaseSecretSpec `json:"spec"`

	// +optional
	Status DatabaseSecretStatus `json:"status,omitempty"`
}

func (s *DatabaseSecret) SetObservedGeneration(generation int64) {
	s.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DatabaseSecretList contains a list of DatabaseSecret.
type DatabaseSecretList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DatabaseSecret `json:"items"`
}

func init() {
	SchemeBuilder.Register(&DatabaseSecret{}, &DatabaseSecretList{})
}
