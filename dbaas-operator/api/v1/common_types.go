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

// ObservedGenerationSetter is implemented by CR root types whose status embeds
// OperatorStatus and therefore can persist the latest reconciled generation.
//
// +kubebuilder:object:generate=false
type ObservedGenerationSetter interface {
	SetObservedGeneration(int64)
}

// Phase represents the processing phase of a dbaas operator resource.
//
// +kubebuilder:validation:Enum=Unknown;Processing;WaitingForDependency;Succeeded;BackingOff;InvalidConfiguration
type Phase string

const (
	PhaseUnknown              Phase = "Unknown"
	PhaseProcessing           Phase = "Processing"
	PhaseWaitingForDependency Phase = "WaitingForDependency"
	PhaseSucceeded            Phase = "Succeeded"
	PhaseBackingOff           Phase = "BackingOff"
	PhaseInvalidConfiguration Phase = "InvalidConfiguration"
)

// OperatorStatus contains common status fields shared by all dbaas operator resources.
type OperatorStatus struct {
	// +kubebuilder:default=Unknown
	// +optional
	Phase Phase `json:"phase,omitempty"`

	// +optional
	ObservedGeneration int64 `json:"observedGeneration,omitempty"`

	// +optional
	// +listType=map
	// +listMapKey=type
	Conditions []metav1.Condition `json:"conditions,omitempty"`
}
