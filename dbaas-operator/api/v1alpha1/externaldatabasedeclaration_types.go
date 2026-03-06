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

// CredentialsSecretRef points to a Kubernetes Secret that holds the username and password
// for a single connection property entry. The Secret must exist in the same namespace
// as the ExternalDatabaseDeclaration CR.
type CredentialsSecretRef struct {
	// name is the name of the Kubernetes Secret containing the credentials.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`

	// usernameKey is the key inside the Secret whose value is the database username.
	// Defaults to "username" if omitted.
	// +optional
	UsernameKey string `json:"usernameKey,omitempty"`

	// passwordKey is the key inside the Secret whose value is the database password.
	// Defaults to "password" if omitted.
	// +optional
	PasswordKey string `json:"passwordKey,omitempty"`
}

// ConnectionProperty describes how to connect to the external database for a specific role.
// The controller assembles a flat map from the typed fields, credentialsSecretRef, and
// extraProperties before sending the request to dbaas-aggregator. The "role" key is
// required by the aggregator and validated server-side.
//
// Assembled flat map sent to dbaas-aggregator per entry:
//
//	{ "role": role, "url": url, "host": host, "port": port, "authDbName": authDbName,
//	  "username": <from Secret>, "password": <from Secret>, <extraProperties...> }
type ConnectionProperty struct {
	// role identifies the access level for this connection entry.
	// The value is adapter-specific, e.g. "admin", "readonly", "readwrite".
	// Required by dbaas-aggregator: every connectionProperties entry must contain a "role" key.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Role string `json:"role"`

	// url is the full connection URL for this role, e.g.
	// "jdbc:postgresql://pg-host:5432/mydb" or "mongodb://mongo-host:27017/mydb".
	// +optional
	URL string `json:"url,omitempty"`

	// host is the hostname or IP address of the database server.
	// +optional
	Host string `json:"host,omitempty"`

	// port is the TCP port of the database server. Stored as a string to keep the
	// CRD schema simple; the aggregator accepts it as-is in the flat map.
	// +optional
	Port string `json:"port,omitempty"`

	// authDbName is the name of the authentication database (relevant for MongoDB and
	// similar engines that separate the auth database from the data database).
	// +optional
	AuthDbName string `json:"authDbName,omitempty"`

	// credentialsSecretRef points to a Kubernetes Secret containing the username and
	// password for this connection. The Secret must exist in the same namespace as the CR.
	// If the Secret is missing at reconcile time, the controller transitions to BackingOff
	// and retries until the Secret becomes available.
	// +optional
	CredentialsSecretRef *CredentialsSecretRef `json:"credentialsSecretRef,omitempty"`

	// extraProperties holds additional adapter-specific connection parameters that are
	// not covered by the typed fields above. All values must be strings.
	// These are merged into the flat map sent to dbaas-aggregator alongside the typed fields.
	// Example: sslMode: "require", connectTimeout: "10".
	// +optional
	ExtraProperties map[string]string `json:"extraProperties,omitempty"`
}

// ExternalDatabaseDeclarationSpec defines the desired state of ExternalDatabaseDeclaration.
//
// The controller calls dbaas-aggregator directly (not via the declarative API) at:
//
//	PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable
//
// where {namespace} is taken from spec.classifier["namespace"].
// The operation is synchronous; there is no async polling (no trackingId).
//
// Field names mirror ExternalDatabaseRequestV3 in the dbaas-aggregator.
type ExternalDatabaseDeclarationSpec struct {
	// classifier is a key-value map that uniquely identifies the external database in dbaas.
	// Typical keys: namespace, microserviceName, scope, tenantId.
	// The aggregator requires at minimum: microserviceName, scope.
	// If scope is "tenant", tenantId must also be present.
	// +kubebuilder:validation:Required
	Classifier map[string]string `json:"classifier"`

	// type is the database engine type, e.g. "postgresql", "mongodb".
	// Must match a type registered in dbaas-aggregator.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// dbName is the name of the logical database as it exists in the external DBMS.
	// Unlike DatabaseDeclaration (where the name is auto-generated), this field is
	// required because the external database already exists with a known name.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	DbName string `json:"dbName"`

	// connectionProperties lists the connection details for each access role.
	// At least one entry is required. Each entry must have a "role" field; the
	// aggregator rejects requests where any entry is missing "role".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	ConnectionProperties []ConnectionProperty `json:"connectionProperties"`

	// updateConnectionProperties controls what happens when dbaas-aggregator already
	// has a record for this classifier+type combination:
	//   false (default) — return the existing record unchanged.
	//   true            — replace the stored connectionProperties with those in this spec.
	// Has no effect when the external database is being registered for the first time.
	// +optional
	UpdateConnectionProperties bool `json:"updateConnectionProperties,omitempty"`
}

// ExternalDatabaseDeclarationStatus defines the observed state of ExternalDatabaseDeclaration.
type ExternalDatabaseDeclarationStatus struct {
	OperatorStatus `json:",inline"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".spec.type"
// +kubebuilder:printcolumn:name="DbName",type="string",JSONPath=".spec.dbName"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// ExternalDatabaseDeclaration is the Schema for the externaldatabasedeclarations API.
// It registers a pre-existing (externally managed) database in dbaas so that
// microservices can discover its connection details through the standard dbaas API.
// Unlike DatabaseDeclaration, the physical database is not created or destroyed by dbaas;
// dbaas only stores and serves its connection properties.
type ExternalDatabaseDeclaration struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitzero"`

	// spec defines the desired state of ExternalDatabaseDeclaration.
	Spec ExternalDatabaseDeclarationSpec `json:"spec"`

	// status defines the observed state of ExternalDatabaseDeclaration.
	// +optional
	Status ExternalDatabaseDeclarationStatus `json:"status,omitzero"`
}

// +kubebuilder:object:root=true

// ExternalDatabaseDeclarationList contains a list of ExternalDatabaseDeclaration.
type ExternalDatabaseDeclarationList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitzero"`
	Items           []ExternalDatabaseDeclaration `json:"items"`
}

func init() {
	SchemeBuilder.Register(&ExternalDatabaseDeclaration{}, &ExternalDatabaseDeclarationList{})
}
