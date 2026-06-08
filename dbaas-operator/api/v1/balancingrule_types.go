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
	// DbMicroserviceBalancingRuleName is the fixed singleton name for
	// microservice balancing rules in each business namespace.
	DbMicroserviceBalancingRuleName = "microservice-balancing-rules"

	// DbNamespaceBalancingRuleName is the fixed singleton name for namespace
	// balancing rules in each business namespace.
	DbNamespaceBalancingRuleName = "namespace-balancing-rules"

	// DbPermanentBalancingRuleName is the fixed singleton name for permanent
	// balancing rules in the operator namespace.
	DbPermanentBalancingRuleName = "permanent-balancing-rules"

	// DbMicroserviceBalancingRuleFinalizer lets the operator disable the
	// previously applied microservice rule in dbaas-aggregator before deletion.
	DbMicroserviceBalancingRuleFinalizer = "platform.dbaas.netcracker.com/dbmicroservicebalancingrule-cleanup"

	// DbNamespaceBalancingRuleFinalizer lets the operator delete the previously
	// applied namespace rules in dbaas-aggregator before deletion.
	DbNamespaceBalancingRuleFinalizer = "platform.dbaas.netcracker.com/dbnamespacebalancingrule-cleanup"

	// DbPermanentBalancingRuleFinalizer lets the operator delete the previously
	// applied permanent rule in dbaas-aggregator before deletion.
	DbPermanentBalancingRuleFinalizer = "platform.dbaas.netcracker.com/dbpermanentbalancingrule-cleanup"
)

// DbMicroserviceBalancingRuleSpec defines an on-microservice physical database
// balancing rule. It maps to the dbaas-aggregator onMicroservices rule payload.
type DbMicroserviceBalancingRuleSpec struct {
	// rules is the set of microservice balancing rules managed by this singleton CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +listType=atomic
	Rules []DbMicroserviceBalancingRuleItem `json:"rules"`
}

// DbMicroserviceBalancingRuleItem defines one on-microservice balancing rule entry.
type DbMicroserviceBalancingRuleItem struct {
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

// DbMicroserviceBalancingRuleAppliedRule tracks one successfully applied
// microservice rule entry for cleanup on update/delete.
type DbMicroserviceBalancingRuleAppliedRule struct {
	// type is the database engine type last applied.
	Type string `json:"type"`

	// microservices is the microservice set last applied for type.
	// +listType=set
	Microservices []string `json:"microservices"`
}

// DbMicroserviceBalancingRuleStatus defines the observed state of DbMicroserviceBalancingRule.
type DbMicroserviceBalancingRuleStatus struct {
	OperatorStatus `json:",inline"`

	// appliedRules are the microservice rule entries last successfully applied
	// to dbaas-aggregator. They are used to clean up removed entries when spec
	// changes. They do not guarantee that referenced physical database labels
	// still resolve.
	// +optional
	// +listType=atomic
	AppliedRules []DbMicroserviceBalancingRuleAppliedRule `json:"appliedRules,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=dbmicroservicebalancingrules,singular=dbmicroservicebalancingrule,shortName=dbmbr
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Rules",type="string",JSONPath=".spec.rules[*].type"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DbMicroserviceBalancingRule is the Schema for the dbmicroservicebalancingrules API.
// It declares a physical database placement rule for specific microservices in a namespace.
type DbMicroserviceBalancingRule struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of DbMicroserviceBalancingRule.
	Spec DbMicroserviceBalancingRuleSpec `json:"spec"`

	// status defines the observed state of DbMicroserviceBalancingRule.
	// +optional
	Status DbMicroserviceBalancingRuleStatus `json:"status,omitempty"`
}

func (r *DbMicroserviceBalancingRule) SetObservedGeneration(generation int64) {
	r.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DbMicroserviceBalancingRuleList contains a list of DbMicroserviceBalancingRule.
type DbMicroserviceBalancingRuleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DbMicroserviceBalancingRule `json:"items"`
}

// DbNamespaceBalancingRuleSpec defines an on-namespace physical database
// balancing rule. It maps to the dbaas-aggregator perNamespace rule payload.
type DbNamespaceBalancingRuleSpec struct {
	// rules is the set of namespace balancing rules managed by this singleton CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +listType=atomic
	Rules []DbNamespaceBalancingRuleItem `json:"rules"`
}

// DbNamespaceBalancingRuleItem defines one on-namespace balancing rule entry.
type DbNamespaceBalancingRuleItem struct {
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

// DbNamespaceBalancingRuleAppliedRule tracks one namespace rule entry last applied.
type DbNamespaceBalancingRuleAppliedRule struct {
	// name is the aggregator rule name last applied.
	Name string `json:"name"`

	// type is the database engine type last applied.
	Type string `json:"type"`

	// physicalDatabaseId is the physical database identifier last applied.
	PhysicalDatabaseID string `json:"physicalDatabaseId"`

	// order is the aggregator rule priority last applied.
	Order int64 `json:"order"`
}

// DbNamespaceBalancingRuleStatus defines the observed state of DbNamespaceBalancingRule.
type DbNamespaceBalancingRuleStatus struct {
	OperatorStatus `json:",inline"`

	// appliedRules are the namespace rule entries last successfully applied
	// to dbaas-aggregator. They are used to delete removed entries when spec
	// changes. They do not guarantee that referenced physical databases exist.
	// +optional
	// +listType=atomic
	AppliedRules []DbNamespaceBalancingRuleAppliedRule `json:"appliedRules,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=dbnamespacebalancingrules,singular=dbnamespacebalancingrule,shortName=dbnbr
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Rules",type="string",JSONPath=".spec.rules[*].type"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DbNamespaceBalancingRule is the Schema for the dbnamespacebalancingrules API.
// It declares a namespace-level physical database placement rule.
type DbNamespaceBalancingRule struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of DbNamespaceBalancingRule.
	Spec DbNamespaceBalancingRuleSpec `json:"spec"`

	// status defines the observed state of DbNamespaceBalancingRule.
	// +optional
	Status DbNamespaceBalancingRuleStatus `json:"status,omitempty"`
}

func (r *DbNamespaceBalancingRule) SetObservedGeneration(generation int64) {
	r.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DbNamespaceBalancingRuleList contains a list of DbNamespaceBalancingRule.
type DbNamespaceBalancingRuleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DbNamespaceBalancingRule `json:"items"`
}

// DbPermanentBalancingRuleSpec defines a permanent namespace physical database
// balancing rule. It maps to the dbaas-aggregator permanent rule payload.
type DbPermanentBalancingRuleSpec struct {
	// rules is the set of permanent balancing rules managed by this singleton CR.
	// +kubebuilder:validation:Required
	// +kubebuilder:validation:MinItems=1
	// +listType=atomic
	Rules []DbPermanentBalancingRuleItem `json:"rules"`
}

// DbPermanentBalancingRuleItem defines one permanent balancing rule entry.
type DbPermanentBalancingRuleItem struct {
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

// DbPermanentBalancingRuleAppliedRule tracks one successfully applied permanent
// rule entry for cleanup on update/delete.
type DbPermanentBalancingRuleAppliedRule struct {
	// dbType is the database engine type last applied.
	DbType string `json:"dbType"`

	// namespaces is the namespace set last applied for dbType.
	// +listType=set
	Namespaces []string `json:"namespaces"`
}

// DbPermanentBalancingRuleStatus defines the observed state of DbPermanentBalancingRule.
type DbPermanentBalancingRuleStatus struct {
	OperatorStatus `json:",inline"`

	// appliedRules are the permanent rule entries last successfully applied
	// to dbaas-aggregator. They are used to delete removed entries when spec
	// changes. They do not guarantee that referenced physical databases exist.
	// +optional
	// +listType=atomic
	AppliedRules []DbPermanentBalancingRuleAppliedRule `json:"appliedRules,omitempty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:resource:scope=Namespaced,path=dbpermanentbalancingrules,singular=dbpermanentbalancingrule,shortName=dbpbr
// +kubebuilder:printcolumn:name="Phase",type="string",JSONPath=".status.phase"
// +kubebuilder:printcolumn:name="Rules",type="string",JSONPath=".spec.rules[*].dbType"
// +kubebuilder:printcolumn:name="Age",type="date",JSONPath=".metadata.creationTimestamp"

// DbPermanentBalancingRule is the Schema for the dbpermanentbalancingrules API.
// It declares a permanent namespace-level physical database placement rule.
type DbPermanentBalancingRule struct {
	metav1.TypeMeta `json:",inline"`

	// metadata is standard object metadata.
	// +optional
	metav1.ObjectMeta `json:"metadata,omitempty"`

	// spec defines the desired state of DbPermanentBalancingRule.
	Spec DbPermanentBalancingRuleSpec `json:"spec"`

	// status defines the observed state of DbPermanentBalancingRule.
	// +optional
	Status DbPermanentBalancingRuleStatus `json:"status,omitempty"`
}

func (r *DbPermanentBalancingRule) SetObservedGeneration(generation int64) {
	r.Status.ObservedGeneration = generation
}

// +kubebuilder:object:root=true

// DbPermanentBalancingRuleList contains a list of DbPermanentBalancingRule.
type DbPermanentBalancingRuleList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DbPermanentBalancingRule `json:"items"`
}

func init() {
	SchemeBuilder.Register(
		&DbMicroserviceBalancingRule{},
		&DbMicroserviceBalancingRuleList{},
		&DbNamespaceBalancingRule{},
		&DbNamespaceBalancingRuleList{},
		&DbPermanentBalancingRule{},
		&DbPermanentBalancingRuleList{},
	)
}
