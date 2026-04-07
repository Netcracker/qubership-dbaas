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

// SecretKeyMapping maps a single key from a Kubernetes Secret to the field name
// expected in the dbaas-aggregator connection payload.
type SecretKeyMapping struct {
	// key is the key in the Secret's data map whose value will be extracted.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Key string `json:"key"`

	// name is the field name in the dbaas-aggregator connection payload that
	// this secret value is mapped to (e.g. "username", "password").
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

// CredentialsSecretRef references a Kubernetes Secret that contains credentials
// for one database connection role.
type CredentialsSecretRef struct {
	// name is the name of the Kubernetes Secret in the same namespace as the
	// ExternalDatabase resource.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`

	// keys is the list of mappings from Secret data keys to dbaas-aggregator
	// connection payload field names. At least one mapping is required.
	// All name values within the list must be unique.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +kubebuilder:validation:XValidations:rule="self.map(k, k.name).toSet().size() == self.size()",message="keys must not contain duplicate name values"
	Keys []SecretKeyMapping `json:"keys"`
}

// ConnectionProperty defines the connection details for a single database access role.
// The controller reads credentials from the referenced Secret and includes them in
// the registration payload sent to dbaas-aggregator.
type ConnectionProperty struct {
	// role is the access role name for this connection entry, e.g. "admin", "readonly".
	// Role names are adapter-specific and must match values known to dbaas-aggregator.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Role string `json:"role"`

	// credentialsSecretRef references a Kubernetes Secret that contains the
	// credentials (e.g. username and password) for this role.
	// If absent, no credentials are sent for this role.
	// +optional
	CredentialsSecretRef *CredentialsSecretRef `json:"credentialsSecretRef,omitempty"`

	// extraProperties is a free-form map of additional adapter-specific connection
	// properties (e.g. host, port, ssl-mode) passed through to dbaas-aggregator as-is.
	// +optional
	ExtraProperties map[string]string `json:"extraProperties,omitempty"`
}

// ExternalDatabaseSpec defines the desired state of ExternalDatabase.
//
// The spec is sent as-is in the registration payload to dbaas-aggregator:
//
//	PUT /api/v3/dbaas/<namespace>/databases/<dbName>/externally_manageable
type ExternalDatabaseSpec struct {
	// classifier is a map of key-value pairs that uniquely identifies the database
	// in dbaas-aggregator. All keys are sorted alphabetically by the aggregator
	// for identity comparison. Typical keys: microserviceName, scope, namespace.
	// +kubebuilder:validation:Required
	Classifier map[string]string `json:"classifier"`

	// type is the database engine type, e.g. "postgresql", "mongodb", "opensearch".
	// Must match a type known to dbaas-aggregator.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// dbName is the logical database name used by dbaas-aggregator to identify
	// this registration. Included in the request URL path.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	DbName string `json:"dbName"`

	// connectionProperties is the list of connection entries, one per access role.
	// Each entry provides the credentials and extra properties for that role.
	// At least one entry is required.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	ConnectionProperties []ConnectionProperty `json:"connectionProperties"`
}

// ExternalDatabaseStatus defines the observed state of ExternalDatabase.
type ExternalDatabaseStatus struct {
	OperatorStatus `json:",inline"`

	// lastRequestId is the X-Request-Id of the most recent reconcile attempt.
	// Use this to correlate operator logs with dbaas-aggregator logs for debugging.
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

// ExternalDatabase registers a pre-existing database instance with dbaas-aggregator
// so that the aggregator can track it and grant access roles.
// The database is not provisioned by dbaas — it must already exist in the DBMS.
type ExternalDatabase struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of ExternalDatabase.
	Spec ExternalDatabaseSpec `json:"spec"`

	// status defines the observed state of ExternalDatabase.
	// +optional
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
