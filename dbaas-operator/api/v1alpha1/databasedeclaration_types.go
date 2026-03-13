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

// ClassifierConfig holds the classifier that uniquely identifies a database in dbaas.
// The classifier is the primary identity key used by dbaas-aggregator to locate or
// create a physical database. Keys are sorted alphabetically by the aggregator for
// identity comparison, so order does not matter here.
type ClassifierConfig struct {
	// classifier is a key-value map that uniquely identifies a database in dbaas.
	// All values must be strings. Typical keys:
	//   - namespace       — Kubernetes namespace of the owning service (required)
	//   - microserviceName — name of the microservice that owns the database (required)
	//   - scope           — logical scope, e.g. "service" or "tenant" (optional)
	//   - tenantId        — tenant identifier for multi-tenant deployments (optional)
	// +kubebuilder:validation:Required
	Classifier map[string]string `json:"classifier"`
}

// VersioningConfig defines the strategy for managing database versions during
// blue-green deployments. Mirrors DatabaseDeclaration.VersioningConfig in the aggregator.
type VersioningConfig struct {
	// approach defines how a new database version is created during a blue-green update.
	// Supported values depend on the dbaas adapter; the aggregator defaults to "clone".
	// +optional
	Approach string `json:"approach,omitempty"`
}

// InitialInstantiation defines how the database is created on the very first deployment.
// Mirrors DatabaseDeclaration.InitialInstantiation in the aggregator.
type InitialInstantiation struct {
	// approach defines the strategy for initial database creation.
	// Supported values depend on the dbaas adapter; the aggregator defaults to "clone".
	// +optional
	Approach string `json:"approach,omitempty"`

	// sourceClassifier is the classifier of the database to clone from.
	// Required when approach is "clone". Must reference an existing database in dbaas.
	// +optional
	SourceClassifier map[string]string `json:"sourceClassifier,omitempty"`
}

// DatabaseDeclarationSpec defines the desired state of DatabaseDeclaration.
//
// This spec is serialized by the controller and sent as the "spec" field of the
// DeclarativePayload body to dbaas-aggregator:
//
//	POST /api/declarations/v1/apply
//	{ "subKind": "DatabaseDeclaration", "spec": <this struct>, ... }
//
// Field names and semantics match the DatabaseDeclaration Java DTO in the aggregator.
type DatabaseDeclarationSpec struct {
	// classifierConfig contains the classifier that uniquely identifies this database
	// in dbaas. The aggregator uses it to look up or create the physical database.
	// +kubebuilder:validation:Required
	ClassifierConfig ClassifierConfig `json:"classifierConfig"`

	// type is the database engine type. Must match a type known to dbaas-aggregator,
	// e.g. "postgresql", "mongodb", "opensearch".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// lazy indicates whether the database should be provisioned on first access rather
	// than immediately during reconciliation. When true, the aggregator defers physical
	// database creation until a service requests a connection. Defaults to false.
	// +optional
	Lazy bool `json:"lazy,omitempty"`

	// settings contains database-engine-specific configuration passed through to the
	// dbaas adapter. The accepted keys and their semantics depend on the specific adapter.
	// All values are strings; numeric and boolean settings should be passed as their
	// string representation (e.g. maxConnections: "100", createOnly: "true").
	// +optional
	Settings map[string]string `json:"settings,omitempty"`

	// namePrefix is a prefix applied to the physical database name created in the DBMS.
	// Useful for disambiguation when multiple logical databases share the same DBMS instance.
	// +optional
	NamePrefix string `json:"namePrefix,omitempty"`

	// versioningConfig defines the strategy for handling database versions during
	// blue-green deployments. Optional; the aggregator applies its own defaults if omitted.
	// +optional
	VersioningConfig *VersioningConfig `json:"versioningConfig,omitempty"`

	// initialInstantiation defines how the database is created on the first deployment.
	// Optional; the aggregator applies its own defaults if omitted.
	// +optional
	InitialInstantiation *InitialInstantiation `json:"initialInstantiation,omitempty"`
}

// DatabaseDeclarationStatus defines the observed state of DatabaseDeclaration.
type DatabaseDeclarationStatus struct {
	OperatorStatus `json:",inline"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".spec.type"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DatabaseDeclaration is the Schema for the databasedeclarations API.
// It declares a logical database that dbaas should provision and manage on behalf
// of the owning microservice.
type DatabaseDeclaration struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitzero"`

	// spec defines the desired state of DatabaseDeclaration.
	Spec DatabaseDeclarationSpec `json:"spec"`

	// status defines the observed state of DatabaseDeclaration.
	// +optional
	Status DatabaseDeclarationStatus `json:"status,omitzero"`
}

// +kubebuilder:object:root=true

// DatabaseDeclarationList contains a list of DatabaseDeclaration.
type DatabaseDeclarationList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitzero"`
	Items           []DatabaseDeclaration `json:"items"`
}

func init() {
	SchemeBuilder.Register(&DatabaseDeclaration{}, &DatabaseDeclarationList{})
}
