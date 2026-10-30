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

// PhysicalDatabaseCredentialsSecretRef references the Secret with the adapter's
// own Basic Auth credentials. The operator reads the fixed keys "username" and
// "password" from it.
type PhysicalDatabaseCredentialsSecretRef struct {
	// name is the Secret name. The Secret must be in the same namespace as the
	// PhysicalDatabase resource.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`
}

// AdapterAPIVersionSpec describes one API spec the adapter serves.
//
// It is part of the status, so it carries no validation markers: the API server
// validates status writes, and a rejected write would drop the conditions too.
type AdapterAPIVersionSpec struct {
	// specRootUrl is the root URL prefix of this API spec, e.g. "/api".
	// +optional
	SpecRootURL string `json:"specRootUrl,omitempty"`

	// major is the latest major version the adapter supports for this spec.
	// +optional
	Major int32 `json:"major,omitempty"`

	// minor is the latest minor version the adapter supports for this spec.
	// +optional
	Minor int32 `json:"minor,omitempty"`

	// supportedMajors lists every major version the adapter accepts for this spec.
	// +optional
	// +listType=atomic
	SupportedMajors []int32 `json:"supportedMajors,omitempty"`
}

// AdapterAPIVersions is the adapter's declared API contract.
type AdapterAPIVersions struct {
	// specs lists the API specs the adapter serves.
	// +optional
	// +listType=atomic
	Specs []AdapterAPIVersionSpec `json:"specs,omitempty"`
}

// PhysicalDatabaseSpec defines the desired state of PhysicalDatabase.
//
// The spec carries only what the adapter cannot report about itself. Everything
// else comes from the adapter's GET /api/v2/adapter/physical_database, and the
// operator sends both to dbaas-aggregator as a physical database registration:
//
//	PUT /api/v3/dbaas/<type>/physical_databases/<physicalDatabaseId>
type PhysicalDatabaseSpec struct {
	// operatorNamespace is the namespace where the dbaas-operator instance
	// responsible for this resource is deployed. It must match that operator's
	// CLOUD_NAMESPACE and is immutable after creation.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	// The Pattern requires an RFC-1123 label, so a value that cannot name a
	// namespace (whitespace, uppercase, a slash, a leading or trailing hyphen)
	// is rejected at admission instead of silently never matching a CLOUD_NAMESPACE.
	// +kubebuilder:validation:MaxLength=63
	// +kubebuilder:validation:Pattern=`^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="spec.operatorNamespace is immutable after creation"
	OperatorNamespace string `json:"operatorNamespace"`

	// adapterAddress is the adapter base URL. The operator probes the adapter at
	// it and sends it to dbaas-aggregator as adapterAddress. The Pattern asks for
	// a scheme, "://", and a non-empty host, which is what the aggregator checks;
	// everything else about the URL is left to the aggregator.
	// The aggregator accepts an address change only when the host stays the same
	// and the scheme changes (a switch to TLS); any other change answers 409.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:Pattern=`^[^\s:/?#]+://[^\s/?#]+`
	AdapterAddress string `json:"adapterAddress"`

	// credentialsSecretRef references the Secret with the adapter's own Basic Auth
	// credentials: the operator uses them to probe the adapter, and dbaas-aggregator
	// uses them to call it.
	// +kubebuilder:validation:Required
	CredentialsSecretRef PhysicalDatabaseCredentialsSecretRef `json:"credentialsSecretRef"`
}

// PhysicalDatabaseStatus defines the observed state of PhysicalDatabase.
//
// Every field next to OperatorStatus is the adapter's answer at the last
// successful probe. A later failed probe leaves them as they are.
type PhysicalDatabaseStatus struct {
	OperatorStatus `json:",inline"`

	// physicalDatabaseId is the adapter-assigned identifier of the database
	// cluster, sent as the {phydbid} path segment. Two resources that report the
	// same identifier conflict, and the older one wins.
	// +optional
	PhysicalDatabaseID string `json:"physicalDatabaseId,omitempty"`

	// type is the database engine type, e.g. "postgresql", sent as the {type}
	// path segment.
	// +optional
	Type string `json:"type,omitempty"`

	// labels are the physical database labels, sent as labels.
	// +optional
	Labels map[string]string `json:"labels,omitempty"`

	// supportedRoles lists the roles the adapter can create for logical
	// databases, sent as metadata.supportedRoles.
	// +optional
	// +listType=atomic
	SupportedRoles []string `json:"supportedRoles,omitempty"`

	// features holds the adapter feature flags, sent as metadata.features.
	// +optional
	Features map[string]bool `json:"features,omitempty"`

	// readOnlyHost is the read-only host of the database cluster, sent as
	// metadata.roHost.
	// +optional
	ReadOnlyHost string `json:"readOnlyHost,omitempty"`

	// apiVersions is the adapter's declared API contract, sent as
	// metadata.apiVersions.
	// +optional
	APIVersions *AdapterAPIVersions `json:"apiVersions,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:selectablefield:JSONPath=".spec.operatorNamespace"
// +kubebuilder:resource:scope=Namespaced,path=physicaldatabases,singular=physicaldatabase,shortName=dbpdr,categories=dbaas
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Ready",type="string",JSONPath=".status.conditions[?(@.type=='Ready')].status"
// +kubebuilder:printcolumn:name="Type",type="string",JSONPath=".status.type"
// +kubebuilder:printcolumn:name="PhysicalDatabaseId",type="string",JSONPath=".status.physicalDatabaseId"
// +kubebuilder:printcolumn:name="Address",type="string",JSONPath=".spec.adapterAddress",priority=1
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// PhysicalDatabase registers a physical database (a DBMS cluster and its dbaas
// adapter) with dbaas-aggregator. The operator does not install the adapter or
// the DBMS; both must already be running. Deleting the resource stops managing
// the registration but does not remove it, because a physical database carries
// logical databases.
type PhysicalDatabase struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of PhysicalDatabase.
	Spec PhysicalDatabaseSpec `json:"spec"`

	// status defines the observed state of PhysicalDatabase.
	// +optional
	Status PhysicalDatabaseStatus `json:"status,omitempty"`
}

func (p *PhysicalDatabase) SetObservedGeneration(generation int64) {
	p.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// PhysicalDatabaseList contains a list of PhysicalDatabase
type PhysicalDatabaseList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []PhysicalDatabase `json:"items"`
}

func init() {
	SchemeBuilder.Register(&PhysicalDatabase{}, &PhysicalDatabaseList{})
}
