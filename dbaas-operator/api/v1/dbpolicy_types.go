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

// ServiceRole defines the database roles granted to a specific microservice.
// Mirrors the ServiceRole Java class in the dbaas-aggregator.
type ServiceRole struct {
	// name is the microservice name (must match the service's app.kubernetes.io/name label).
	// The aggregator uses this to associate role grants with the correct service identity.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`

	// roles is the list of database roles granted to this microservice.
	// Role names are adapter-specific, e.g. "admin", "readonly", "readwrite".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	Roles []string `json:"roles"`
}

// PolicyRole defines default and additional database role rules for a specific database type.
// Mirrors the PolicyRole Java class in the dbaas-aggregator.
type PolicyRole struct {
	// type is the database engine type this policy applies to, e.g. "postgresql", "mongodb".
	// Must match a type known to dbaas-aggregator.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// defaultRole is the database role assigned to any microservice that is not
	// explicitly listed in the services section. Allows a baseline access level
	// for all services without individual entries.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	DefaultRole string `json:"defaultRole"`

	// additionalRole lists extra roles that may be granted beyond the defaultRole.
	// Interpretation is adapter-specific.
	// +optional
	AdditionalRole []string `json:"additionalRole,omitempty"`
}

// DbPolicySpec defines the desired state of DbPolicy.
//
// This spec is serialized by the controller and sent as the "spec" field of the
// DeclarativePayload body to dbaas-aggregator:
//
//	POST /api/declarations/v1/apply
//	{ "subKind": "DbPolicy", "metadata": { "microserviceName": <microserviceName> }, "spec": <roles>, ... }
//
// Field names and semantics match the RolesRegistration Java class in the aggregator.
// At least one of services or policy must be provided.
type DbPolicySpec struct {
	// microserviceName is the microservice that owns this policy.
	// Mapped to metadata.microserviceName in the DBaaS declarative payload.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	MicroserviceName string `json:"microserviceName"`

	// services defines per-microservice database role assignments. Each entry grants
	// a named microservice a specific set of database roles in this namespace.
	// +optional
	Services []ServiceRole `json:"services,omitempty"`

	// policy defines default and additional role rules per database type. Entries here
	// act as a fallback for services not explicitly listed in the services field.
	// +optional
	Policy []PolicyRole `json:"policy,omitempty"`

	// disableGlobalPermissions disables the default global permission grants that
	// dbaas-aggregator would otherwise apply to all databases of the service.
	// Set to true to opt out of global defaults and rely solely on explicit entries.
	// +optional
	DisableGlobalPermissions bool `json:"disableGlobalPermissions,omitempty"`
}

// DbPolicyStatus defines the observed state of DbPolicy.
type DbPolicyStatus struct {
	OperatorStatus `json:",inline"`

	// lastRequestId is the X-Request-Id of the most recent reconcile attempt.
	// Use this to correlate operator logs with dbaas-aggregator logs for debugging.
	// +optional
	LastRequestID string `json:"lastRequestId,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=dbpolicies,singular=dbpolicy,shortName=dbbp
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DbPolicy is the Schema for the dbpolicies API.
// It declares the database role assignments for microservices in a namespace,
// applied by dbaas-aggregator when provisioning or connecting to databases.
type DbPolicy struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of DbPolicy.
	Spec DbPolicySpec `json:"spec"`

	// status defines the observed state of DbPolicy.
	// +optional
	Status DbPolicyStatus `json:"status,omitempty"`
}

func (p *DbPolicy) SetObservedGeneration(generation int64) {
	p.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DbPolicyList contains a list of DbPolicy.
type DbPolicyList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DbPolicy `json:"items"`
}

func init() {
	SchemeBuilder.Register(&DbPolicy{}, &DbPolicyList{})
}
