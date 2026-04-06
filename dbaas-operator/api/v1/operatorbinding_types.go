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
	// OperatorBindingName is the only allowed name for an OperatorBinding resource.
	// There can be at most one OperatorBinding per namespace, always named "registration".
	OperatorBindingName = "registration"

	// OperatorBindingProtectionFinalizer is added to an OperatorBinding when the
	// namespace contains blocking resources (ExternalDatabase, DatabaseDeclaration,
	// DbPolicy). Removal of the finalizer is blocked until those resources are gone,
	// preventing accidental ownership release while workloads are still running.
	OperatorBindingProtectionFinalizer = "platform.dbaas.netcracker.com/binding-protection"
)

// OperatorBindingSpec declares the desired namespace ownership.
type OperatorBindingSpec struct {
	// Location is the logical location (e.g. cloud namespace name) that this binding
	// registers with dbaas-aggregator.  It is immutable after creation — change it by
	// deleting and re-creating the OperatorBinding.
	//
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="spec.location is immutable after creation"
	Location string `json:"location"`
}

// +kubebuilder:object:root=true
// +kubebuilder:resource:scope=Namespaced,path=operatorbindings,singular=operatorbinding,shortName=ob
// +kubebuilder:printcolumn:name="Location",type=string,JSONPath=".spec.location"
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=".metadata.creationTimestamp"
// +kubebuilder:validation:XValidation:rule="self.metadata.name == 'registration'",message="OperatorBinding name must be 'registration'"

// OperatorBinding claims namespace ownership for the dbaas-operator.
// Exactly one OperatorBinding named "registration" may exist per namespace.
// While an OperatorBinding exists the operator will reconcile dbaas workload
// resources (ExternalDatabase, DatabaseDeclaration, DbPolicy) in that namespace.
// Deleting the OperatorBinding releases ownership; the finalizer prevents deletion
// while blocking workload resources still exist in the namespace.
type OperatorBinding struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec OperatorBindingSpec `json:"spec"`
}

// +kubebuilder:object:root=true

// OperatorBindingList contains a list of OperatorBinding.
type OperatorBindingList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []OperatorBinding `json:"items"`
}

func init() {
	SchemeBuilder.Register(&OperatorBinding{}, &OperatorBindingList{})
}
