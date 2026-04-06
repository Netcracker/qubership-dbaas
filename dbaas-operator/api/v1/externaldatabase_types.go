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

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

type SecretKeyMapping struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Key string `json:"key"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

type CredentialsSecretRef struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +kubebuilder:validation:XValidations:rule="self.map(k, k.name).toSet().size() == self.size()",message="keys must not contain duplicate name values"
	Keys []SecretKeyMapping `json:"keys"`
}

type ConnectionProperty struct {
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Role string `json:"role"`

	// +optional
	CredentialsSecretRef *CredentialsSecretRef `json:"credentialsSecretRef,omitempty"`

	// +optional
	ExtraProperties map[string]string `json:"extraProperties,omitempty"`
}

// ExternalDatabaseSpec defines the desired state of ExternalDatabase.
type ExternalDatabaseSpec struct {
	// +kubebuilder:validation:Required
	Classifier map[string]string `json:"classifier"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	DbName string `json:"dbName"`

	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	ConnectionProperties []ConnectionProperty `json:"connectionProperties"`
}

// ExternalDatabaseStatus defines the observed state of ExternalDatabase.
type ExternalDatabaseStatus struct {
	OperatorStatus `json:",inline"`

	// +optional
	LastRequestID string `json:"lastRequestId,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=externaldatabases,singular=externaldatabase,shortName=edb
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".spec.type"
// +kubebuilder:printcolumn:name="DbName",type="string",JSONPath=".spec.dbName"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// ExternalDatabase is the Schema for the externaldatabases API.
type ExternalDatabase struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ExternalDatabaseSpec   `json:"spec"`
	Status ExternalDatabaseStatus `json:"status,omitempty"`
}

func (e *ExternalDatabase) SetObservedGeneration(generation int64) {
	e.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// ExternalDatabaseList contains a list of ExternalDatabase.
type ExternalDatabaseList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ExternalDatabase `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ExternalDatabase{}, &ExternalDatabaseList{})
}
