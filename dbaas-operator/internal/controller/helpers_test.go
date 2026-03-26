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
	"testing"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// TestSetCondition_LastTransitionTime verifies that LastTransitionTime follows
// Kubernetes API conventions: it is preserved when Status is unchanged,
// even if Reason or Message change; it is updated only when Status changes.
func TestSetCondition_LastTransitionTime(t *testing.T) {
	t.Parallel()

	past := metav1.NewTime(time.Now().Add(-1 * time.Hour))

	seed := func() []metav1.Condition {
		return []metav1.Condition{{
			Type:               conditionTypeReady,
			Status:             metav1.ConditionFalse,
			Reason:             "AggregatorError",
			Message:            "server error",
			LastTransitionTime: past,
			ObservedGeneration: 1,
		}}
	}

	t.Run("same Status, same Reason — time preserved", func(t *testing.T) {
		t.Parallel()
		conds := seed()
		setCondition(&conds, 1, conditionTypeReady, metav1.ConditionFalse, "AggregatorError", "server error")
		if !conds[0].LastTransitionTime.Equal(&past) {
			t.Errorf("expected time to be preserved, got %v", conds[0].LastTransitionTime)
		}
	})

	t.Run("same Status, different Reason — time preserved", func(t *testing.T) {
		t.Parallel()
		conds := seed()
		setCondition(&conds, 1, conditionTypeReady, metav1.ConditionFalse, "Unauthorized", "401")
		if !conds[0].LastTransitionTime.Equal(&past) {
			t.Errorf("expected time to be preserved on Reason change, got %v", conds[0].LastTransitionTime)
		}
	})

	t.Run("same Status, different Message — time preserved", func(t *testing.T) {
		t.Parallel()
		conds := seed()
		setCondition(&conds, 1, conditionTypeReady, metav1.ConditionFalse, "AggregatorError", "new message")
		if !conds[0].LastTransitionTime.Equal(&past) {
			t.Errorf("expected time to be preserved on Message change, got %v", conds[0].LastTransitionTime)
		}
	})

	t.Run("Status changes — time updated", func(t *testing.T) {
		t.Parallel()
		conds := seed()
		before := time.Now()
		setCondition(&conds, 1, conditionTypeReady, metav1.ConditionTrue, "Registered", "")
		if !conds[0].LastTransitionTime.After(before) {
			t.Errorf("expected time to be updated on Status change, got %v", conds[0].LastTransitionTime)
		}
	})
}
