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

const (
	// MicroserviceBalancingRuleName is the fixed singleton name for
	// microservice balancing rules in each business namespace.
	MicroserviceBalancingRuleName = "microservice-balancing-rules"

	// NamespaceBalancingRuleName is the fixed singleton name for namespace
	// balancing rules in each business namespace.
	NamespaceBalancingRuleName = "namespace-balancing-rules"

	// PermanentBalancingRuleName is the fixed singleton name for permanent
	// balancing rules in the operator namespace.
	PermanentBalancingRuleName = "permanent-balancing-rules"

	// MicroserviceBalancingRuleFinalizer lets the operator disable the
	// previously applied microservice rule in dbaas-aggregator before deletion.
	MicroserviceBalancingRuleFinalizer = "platform.dbaas.netcracker.com/microservicebalancingrules-cleanup"

	// NamespaceBalancingRuleFinalizer lets the operator delete the previously
	// applied namespace rules in dbaas-aggregator before deletion.
	NamespaceBalancingRuleFinalizer = "platform.dbaas.netcracker.com/namespacebalancingrules-cleanup"

	// PermanentBalancingRuleFinalizer lets the operator delete the previously
	// applied permanent rule in dbaas-aggregator before deletion.
	PermanentBalancingRuleFinalizer = "platform.dbaas.netcracker.com/permanentbalancingrules-cleanup"
)

// MicroserviceBalancingRuleSpec defines an on-microservice physical database
// balancing rule. It maps to the dbaas-aggregator onMicroservices rule payload.
type MicroserviceBalancingRuleSpec struct {
	// rules is the set of microservice balancing rules managed by this singleton CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +listType=atomic
	Rules []MicroserviceBalancingRuleItem `json:"rules"`
}

// MicroserviceBalancingRuleItem defines one on-microservice balancing rule entry.
type MicroserviceBalancingRuleItem struct {
	// type is the database engine type this rule applies to, e.g. "postgresql" or "mongodb".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// microservices is the list of microservice names to which the rule applies.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +kubebuilder:validation:items:MinLength=1
	// +listType=set
	Microservices []string `json:"microservices"`

	// label is the physical database label expression used by dbaas-aggregator
	// to resolve exactly one physical database, e.g. "zone=fast". The operator
	// validates this value for shape only; a Succeeded status means
	// dbaas-aggregator accepted the rule, not that future provisioning is
	// guaranteed to find a matching physical database.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:Pattern=`^[^=]+=[^=]+$`
	Label string `json:"label"`
}

// MicroserviceBalancingRuleAppliedRule tracks one successfully applied
// microservice rule entry for cleanup on update/delete.
type MicroserviceBalancingRuleAppliedRule struct {
	// type is the database engine type last applied.
	Type string `json:"type"`

	// microservices is the microservice set last applied for type.
	// +listType=set
	Microservices []string `json:"microservices"`
}

// MicroserviceBalancingRuleStatus defines the observed state of MicroserviceBalancingRule.
type MicroserviceBalancingRuleStatus struct {
	OperatorStatus `json:",inline"`

	// appliedRules are the microservice rule entries last successfully applied
	// to dbaas-aggregator. They are used to clean up removed entries when spec
	// changes. They do not guarantee that referenced physical database labels
	// still resolve.
	// +optional
	// +listType=atomic
	AppliedRules []MicroserviceBalancingRuleAppliedRule `json:"appliedRules,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=microservicebalancingrules,singular=microservicebalancingrule,shortName=dbmbr,categories=dbaas
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Ready",type="string",JSONPath=".status.conditions[?(@.type=='Ready')].status"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// MicroserviceBalancingRule is the Schema for the microservicebalancingrules API.
// It declares a physical database placement rule for specific microservices in a namespace.
type MicroserviceBalancingRule struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of MicroserviceBalancingRule.
	Spec MicroserviceBalancingRuleSpec `json:"spec"`

	// status defines the observed state of MicroserviceBalancingRule.
	// +optional
	Status MicroserviceBalancingRuleStatus `json:"status,omitempty"`
}

func (r *MicroserviceBalancingRule) SetObservedGeneration(generation int64) {
	r.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// MicroserviceBalancingRuleList contains a list of MicroserviceBalancingRule.
type MicroserviceBalancingRuleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []MicroserviceBalancingRule `json:"items"`
}

// NamespaceBalancingRuleSpec defines an on-namespace physical database
// balancing rule. It maps to the dbaas-aggregator perNamespace rule payload.
type NamespaceBalancingRuleSpec struct {
	// rules is the set of namespace balancing rules managed by this singleton CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +listType=atomic
	Rules []NamespaceBalancingRuleItem `json:"rules"`
}

// NamespaceBalancingRuleItem defines one on-namespace balancing rule entry.
type NamespaceBalancingRuleItem struct {
	// name is the aggregator rule name used in the namespace balancing rule endpoint.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Name string `json:"name"`

	// type is the database engine type this rule applies to, e.g. "postgresql" or "mongodb".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	Type string `json:"type"`

	// physicalDatabaseId is the physical database identifier where new logical
	// databases of this type should be created. The operator validates this
	// value for shape only; a Succeeded status means dbaas-aggregator stored
	// the rule, not that this identifier was resolved to a registered physical
	// database.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	PhysicalDatabaseID string `json:"physicalDatabaseId"`

	// order is the aggregator rule priority. Higher order wins when multiple
	// namespace rules match the same database type.
	// +kubebuilder:validation:Required
	Order int64 `json:"order"`
}

// NamespaceBalancingRuleAppliedRule tracks one namespace rule entry last applied.
type NamespaceBalancingRuleAppliedRule struct {
	// name is the aggregator rule name last applied.
	Name string `json:"name"`

	// type is the database engine type last applied.
	Type string `json:"type"`

	// physicalDatabaseId is the physical database identifier last applied.
	PhysicalDatabaseID string `json:"physicalDatabaseId"`

	// order is the aggregator rule priority last applied.
	Order int64 `json:"order"`
}

// NamespaceBalancingRuleStatus defines the observed state of NamespaceBalancingRule.
type NamespaceBalancingRuleStatus struct {
	OperatorStatus `json:",inline"`

	// appliedRules are the namespace rule entries last successfully applied
	// to dbaas-aggregator. They are used to delete removed entries when spec
	// changes. They do not guarantee that referenced physical databases exist.
	// +optional
	// +listType=atomic
	AppliedRules []NamespaceBalancingRuleAppliedRule `json:"appliedRules,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=namespacebalancingrules,singular=namespacebalancingrule,shortName=dbnbr,categories=dbaas
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Ready",type="string",JSONPath=".status.conditions[?(@.type=='Ready')].status"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// NamespaceBalancingRule is the Schema for the namespacebalancingrules API.
// It declares a namespace-level physical database placement rule.
type NamespaceBalancingRule struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of NamespaceBalancingRule.
	Spec NamespaceBalancingRuleSpec `json:"spec"`

	// status defines the observed state of NamespaceBalancingRule.
	// +optional
	Status NamespaceBalancingRuleStatus `json:"status,omitempty"`
}

func (r *NamespaceBalancingRule) SetObservedGeneration(generation int64) {
	r.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// NamespaceBalancingRuleList contains a list of NamespaceBalancingRule.
type NamespaceBalancingRuleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []NamespaceBalancingRule `json:"items"`
}

// PermanentBalancingRuleSpec defines a permanent namespace physical database
// balancing rule. It maps to the dbaas-aggregator permanent rule payload.
type PermanentBalancingRuleSpec struct {
	// rules is the set of permanent balancing rules managed by this singleton CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +listType=atomic
	Rules []PermanentBalancingRuleItem `json:"rules"`
}

// PermanentBalancingRuleItem defines one permanent balancing rule entry.
type PermanentBalancingRuleItem struct {
	// dbType is the database engine type this rule applies to, e.g. "postgresql" or "mongodb".
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	DbType string `json:"dbType"`

	// physicalDatabaseId is the physical database identifier where new logical
	// databases of this type should be created. The operator validates this
	// value for shape only; a Succeeded status means dbaas-aggregator stored
	// the rule, not that this identifier was resolved to a registered physical
	// database.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinLength=1
	PhysicalDatabaseID string `json:"physicalDatabaseId"`

	// namespaces is the list of namespaces where the permanent rule applies.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +kubebuilder:validation:items:MinLength=1
	// +listType=set
	Namespaces []string `json:"namespaces"`
}

// PermanentBalancingRuleAppliedRule tracks one successfully applied permanent
// rule entry for cleanup on update/delete.
type PermanentBalancingRuleAppliedRule struct {
	// dbType is the database engine type last applied.
	DbType string `json:"dbType"`

	// namespaces is the namespace set last applied for dbType.
	// +listType=set
	Namespaces []string `json:"namespaces"`
}

// PermanentBalancingRuleStatus defines the observed state of PermanentBalancingRule.
type PermanentBalancingRuleStatus struct {
	OperatorStatus `json:",inline"`

	// appliedRules are the permanent rule entries last successfully applied
	// to dbaas-aggregator. They are used to delete removed entries when spec
	// changes. They do not guarantee that referenced physical databases exist.
	// +optional
	// +listType=atomic
	AppliedRules []PermanentBalancingRuleAppliedRule `json:"appliedRules,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=permanentbalancingrules,singular=permanentbalancingrule,shortName=dbpbr,categories=dbaas
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Ready",type="string",JSONPath=".status.conditions[?(@.type=='Ready')].status"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// PermanentBalancingRule is the Schema for the permanentbalancingrules API.
// It declares a permanent namespace-level physical database placement rule.
type PermanentBalancingRule struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of PermanentBalancingRule.
	Spec PermanentBalancingRuleSpec `json:"spec"`

	// status defines the observed state of PermanentBalancingRule.
	// +optional
	Status PermanentBalancingRuleStatus `json:"status,omitempty"`
}

func (r *PermanentBalancingRule) SetObservedGeneration(generation int64) {
	r.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// PermanentBalancingRuleList contains a list of PermanentBalancingRule.
type PermanentBalancingRuleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []PermanentBalancingRule `json:"items"`
}

func init() {
	SchemeBuilder.Register(
		&MicroserviceBalancingRule{},
		&MicroserviceBalancingRuleList{},
		&NamespaceBalancingRule{},
		&NamespaceBalancingRuleList{},
		&PermanentBalancingRule{},
		&PermanentBalancingRuleList{},
	)
}
