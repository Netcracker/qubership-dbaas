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

// Classifier uniquely identifies a database in dbaas-aggregator.
// All keys are sorted alphabetically by the aggregator for identity comparison.
type Classifier struct {
	// microserviceName is the name of the microservice that owns the database.
	// Must match metadata.microserviceName sent in the declarative payload.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	MicroserviceName string `json:"microserviceName"`

	// scope defines the logical scope of the database, e.g. "service" or "tenant".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Scope string `json:"scope"`

	// namespace is the Kubernetes namespace of the owning service.
	// If omitted, the aggregator uses metadata.namespace from the request.
	// +optional
	Namespace string `json:"namespace,omitempty"`

	// tenantId is the tenant identifier for multi-tenant deployments.
	// Only relevant when scope="tenant". When absent, the aggregator applies
	// the declaration for every tenant already registered in the namespace.
	// +optional
	TenantId string `json:"tenantId,omitempty"`

	// customKeys is an optional nested map for adapter-specific or
	// application-specific identifiers (e.g. logicalDBName).
	// Values are arbitrary strings; not validated by the aggregator.
	// +optional
	CustomKeys map[string]string `json:"customKeys,omitempty"`
}

// ClassifierConfig holds the classifier that uniquely identifies a database in dbaas.
type ClassifierConfig struct {
	// classifier is the set of keys that uniquely identify this database.
	// Required keys: microserviceName, scope.
	// +kubebuilder:validation:Required
	Classifier Classifier `json:"classifier"`
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
	// "clone" — clone from sourceClassifier (lazy=true is prohibited in this mode).
	// "new"   — create an empty database (default when initialInstantiation is absent).
	// +optional
	Approach string `json:"approach,omitempty"`

	// sourceClassifier is the classifier of the database to clone from.
	// Required when approach is "clone". Must reference an existing database in dbaas.
	// microserviceName must match classifierConfig.classifier.microserviceName.
	// +optional
	SourceClassifier *Classifier `json:"sourceClassifier,omitempty"`
}

// DatabaseDeclarationSpec defines the desired state of DatabaseDeclaration.
//
// This spec is serialized by the controller and sent as the "spec" field of the
// DeclarativePayload body to dbaas-aggregator:
//
//	POST /api/declarations/v1/apply
//	{ "kind": "DBaaS", "subKind": "DatabaseDeclaration", "spec": <this struct>, ... }
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
	// than immediately during reconciliation. Defaults to false.
	// Prohibited when initialInstantiation.approach=clone.
	// +optional
	Lazy bool `json:"lazy,omitempty"`

	// settings contains database-engine-specific configuration passed through to the
	// dbaas adapter. All values are strings.
	// +optional
	Settings map[string]string `json:"settings,omitempty"`

	// namePrefix is a prefix applied to the physical database name created in the DBMS.
	// +optional
	NamePrefix string `json:"namePrefix,omitempty"`

	// versioningConfig defines the strategy for handling database versions during
	// blue-green deployments. If absent — versioningType="static" (no versioning).
	// +optional
	VersioningConfig *VersioningConfig `json:"versioningConfig,omitempty"`

	// initialInstantiation defines how the database is created on the first deployment.
	// If absent — instantiationApproach defaults to "new".
	// +optional
	InitialInstantiation *InitialInstantiation `json:"initialInstantiation,omitempty"`
}

// DatabaseDeclarationStatus defines the observed state of DatabaseDeclaration.
type DatabaseDeclarationStatus struct {
	OperatorStatus `json:",inline"`

	// pendingOperationGeneration stores the .metadata.generation at which the
	// current status.trackingId was obtained. The controller uses this to detect
	// spec changes that occur while an async operation is in progress: if the
	// current generation differs from pendingOperationGeneration, the stale
	// trackingId is cleared and the operation is re-submitted.
	// Zero means no pending async operation.
	// +optional
	PendingOperationGeneration int64 `json:"pendingOperationGeneration,omitempty"`
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
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of DatabaseDeclaration.
	Spec DatabaseDeclarationSpec `json:"spec"`

	// status defines the observed state of DatabaseDeclaration.
	// +optional
	Status DatabaseDeclarationStatus `json:"status,omitempty"`
}

func (d *DatabaseDeclaration) SetObservedGeneration(generation int64) {
	d.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DatabaseDeclarationList contains a list of DatabaseDeclaration.
type DatabaseDeclarationList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DatabaseDeclaration `json:"items"`
}

func init() {
	SchemeBuilder.Register(&DatabaseDeclaration{}, &DatabaseDeclarationList{})
}
