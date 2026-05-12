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
	// NamespaceBindingName is the only allowed name for a NamespaceBinding resource.
	// There can be at most one NamespaceBinding per namespace, always named "binding".
	NamespaceBindingName = "binding"

	// NamespaceBindingProtectionFinalizer is added to a NamespaceBinding when the
	// namespace contains dbaas workload resources managed by this operator.
	// Removal of the finalizer is blocked until those resources are gone,
	// preventing accidental ownership release while workloads are still running.
	NamespaceBindingProtectionFinalizer = "platform.dbaas.netcracker.com/binding-protection"
)

// NamespaceBindingSpec declares the desired namespace ownership.
type NamespaceBindingSpec struct {
	// OperatorNamespace is the Kubernetes namespace where the dbaas-operator instance
	// that owns this namespace is deployed (its CLOUD_NAMESPACE).
	// It is immutable after creation — change it by deleting and re-creating the NamespaceBinding.
	//
	// +kubebuilder:validation:MinLength=1
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="spec.operatorNamespace is immutable after creation"
	OperatorNamespace string `json:"operatorNamespace"`
}

// +kubebuilder:object:root=true
// +kubebuilder:resource:scope=Namespaced,path=namespacebindings,singular=namespacebinding,shortName=dbnb
// +kubebuilder:printcolumn:name="OperatorNamespace",type=string,JSONPath=".spec.operatorNamespace"
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=".metadata.creationTimestamp"
// +kubebuilder:validation:XValidation:rule="self.metadata.name == 'binding'",message="NamespaceBinding name must be 'binding'"

// NamespaceBinding claims namespace ownership for the dbaas-operator.
// Exactly one NamespaceBinding named "binding" may exist per namespace.
// While a NamespaceBinding exists the operator will reconcile dbaas workload
// resources in that namespace.
// Deleting the NamespaceBinding releases ownership; the finalizer prevents deletion
// while blocking workload resources still exist in the namespace.
type NamespaceBinding struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec NamespaceBindingSpec `json:"spec"`
}

// +kubebuilder:object:root=true

// NamespaceBindingList contains a list of NamespaceBinding.
type NamespaceBindingList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []NamespaceBinding `json:"items"`
}

func init() {
	SchemeBuilder.Register(&NamespaceBinding{}, &NamespaceBindingList{})
}
