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

// VersioningConfig defines the strategy for managing database versions during
// blue-green deployments. Mirrors InternalDatabase.VersioningConfig in the aggregator.
type VersioningConfig struct {
	// approach defines how a new database version is created during a blue-green update.
	// Supported values depend on the dbaas adapter; the aggregator defaults to "clone".
	// +optional
	Approach string `json:"approach,omitempty"`
}

// InitialInstantiation defines how the database is created on the very first deployment.
// Mirrors InternalDatabase.InitialInstantiation in the aggregator.
type InitialInstantiation struct {
	// approach defines the strategy for initial database creation.
	// "clone" — clone from sourceClassifier (lazy=true is prohibited in this mode).
	// "new"   — create an empty database (default when initialInstantiation is absent).
	// +optional
	Approach string `json:"approach,omitempty"`

	// sourceClassifier is the classifier of the database to clone from.
	// Required when approach is "clone". Must reference an existing database in dbaas.
	// microserviceName must match classifier.microserviceName.
	// +optional
	SourceClassifier *Classifier `json:"sourceClassifier,omitempty"`
}

// InternalDatabaseSpec defines the desired state of InternalDatabase.
//
// This spec is serialized by the controller and sent as the "spec" field of the
// DeclarativePayload body to dbaas-aggregator:
//
//	POST /api/declarations/v1/apply
//	{ "kind": "DBaaS", "subKind": "DatabaseDeclaration", "spec": <this struct>, ... }
//
// Field names and semantics match the InternalDatabase Java DTO in the aggregator.
type InternalDatabaseSpec struct {
	// classifier uniquely identifies this database in dbaas.
	// The aggregator uses it to look up or create the physical database.
	// Required keys: microserviceName, scope.
	// Immutable after creation — changing the classifier of an existing
	// InternalDatabase would switch the CR onto a different database while
	// the controller's status (trackingID, observedGeneration) still references
	// the original one. To rebind to a different database, delete and recreate
	// the CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="spec.classifier is immutable after creation"
	Classifier Classifier `json:"classifier"`

	// type is the database engine type. Must match a type known to dbaas-aggregator,
	// e.g. "postgresql", "mongodb", "opensearch".
	// Immutable after creation — changing engine type mid-flight would request
	// provisioning of a fresh database on a different adapter while the original
	// remains registered under the same CR identity.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="spec.type is immutable after creation"
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

// InternalDatabaseStatus defines the observed state of InternalDatabase.
type InternalDatabaseStatus struct {
	OperatorStatus `json:",inline"`

	// trackingID is the identifier returned by dbaas-aggregator when a database
	// provisioning operation is started asynchronously (HTTP 202). The controller
	// stores it here and uses it to poll the operation status on subsequent reconciles.
	// Cleared once the operation reaches a terminal state (Completed or Failed).
	// +optional
	TrackingID string `json:"trackingID,omitempty"`

	// pendingOperationGeneration stores the .metadata.generation at which the
	// current trackingID was obtained. The controller uses this to detect spec
	// changes that occur while an async operation is in progress: if the current
	// generation differs from pendingOperationGeneration, the stale trackingID is
	// cleared and the operation is re-submitted.
	// Zero means no pending async operation.
	// +optional
	PendingOperationGeneration int64 `json:"pendingOperationGeneration,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=internaldatabases,singular=internaldatabase,shortName=dbidb,categories=dbaas
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Ready",type="string",JSONPath=".status.conditions[?(@.type=='Ready')].status"
// +kubebuilder:printcolumn:name="MicroserviceName",type="string",JSONPath=".spec.classifier.microserviceName"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".spec.type"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// InternalDatabase is the Schema for the internaldatabases API.
// It declares a logical database that dbaas should provision and manage on behalf
// of the owning microservice.
type InternalDatabase struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of InternalDatabase.
	Spec InternalDatabaseSpec `json:"spec"`

	// status defines the observed state of InternalDatabase.
	// +optional
	Status InternalDatabaseStatus `json:"status,omitempty"`
}

func (d *InternalDatabase) SetObservedGeneration(generation int64) {
	d.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// InternalDatabaseList contains a list of InternalDatabase.
type InternalDatabaseList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []InternalDatabase `json:"items"`
}

func init() {
	SchemeBuilder.Register(&InternalDatabase{}, &InternalDatabaseList{})
}
