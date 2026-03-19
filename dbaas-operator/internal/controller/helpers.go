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

package controller

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// setCondition upserts a metav1.Condition in the given slice.
// LastTransitionTime is preserved only when both Status and Reason are
// unchanged. A change in either field is considered a transition so that
// diagnostics tools can tell exactly when the error category shifted
// (e.g. SecretError → Unauthorized at the same Status=False).
func setCondition(
	conditions *[]metav1.Condition,
	generation int64,
	condType string,
	status metav1.ConditionStatus,
	reason, message string,
) {
	now := metav1.Now()
	cond := metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             reason,
		Message:            message,
		LastTransitionTime: now,
		ObservedGeneration: generation,
	}

	for i, existing := range *conditions {
		if existing.Type == condType {
			if existing.Status == status && existing.Reason == reason {
				// Neither Status nor Reason changed: preserve the transition time.
				cond.LastTransitionTime = existing.LastTransitionTime
			}
			(*conditions)[i] = cond
			return
		}
	}
	*conditions = append(*conditions, cond)
}
