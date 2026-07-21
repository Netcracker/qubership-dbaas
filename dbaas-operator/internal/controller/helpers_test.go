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
		before := metav1.Now()
		setCondition(&conds, 1, conditionTypeReady, metav1.ConditionTrue, "Registered", "")
		// Use !Before (i.e. >=) rather than After (>) to avoid a spurious failure
		// when both time.Now() calls land on the same nanosecond on a fast machine.
		if conds[0].LastTransitionTime.Before(&before) {
			t.Errorf("expected LastTransitionTime >= %v on Status change, got %v", before, conds[0].LastTransitionTime)
		}
	})
}

// cond builds a condition for the predicate tests below.
func cond(condType string, status metav1.ConditionStatus, generation int64) metav1.Condition {
	return metav1.Condition{
		Type:               condType,
		Status:             status,
		Reason:             "Test",
		ObservedGeneration: generation,
	}
}

// TestIsTerminal covers the predicate that replaced the former
// "phase == Succeeded || phase == InvalidConfiguration" check: a resource is
// terminal once it either succeeded (Ready=True) or hit a permanent error
// (Stalled=True). Transient failures and in-flight work are not terminal.
func TestIsTerminal(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name  string
		conds []metav1.Condition
		want  bool
	}{
		{"no conditions yet", nil, false},
		{"succeeded", []metav1.Condition{cond(conditionTypeReady, metav1.ConditionTrue, 1)}, true},
		{
			name: "permanent error",
			conds: []metav1.Condition{
				cond(conditionTypeReady, metav1.ConditionFalse, 1),
				cond(conditionTypeStalled, metav1.ConditionTrue, 1),
			},
			want: true,
		},
		{
			name: "transient error keeps retrying",
			conds: []metav1.Condition{
				cond(conditionTypeReady, metav1.ConditionFalse, 1),
				cond(conditionTypeStalled, metav1.ConditionFalse, 1),
			},
			want: false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isTerminal(tc.conds); got != tc.want {
				t.Errorf("isTerminal() = %v, want %v", got, tc.want)
			}
		})
	}
}

// TestIsReadyForGeneration covers the predicate that replaced the former
// "observedGeneration >= generation && phase == Succeeded" check. A Ready
// condition left over from an earlier generation must not count as success for
// the current spec.
func TestIsReadyForGeneration(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name       string
		conds      []metav1.Condition
		generation int64
		want       bool
	}{
		{"no conditions yet", nil, 1, false},
		{"ready for current generation", []metav1.Condition{cond(conditionTypeReady, metav1.ConditionTrue, 2)}, 2, true},
		{"ready observed after a later reconcile", []metav1.Condition{cond(conditionTypeReady, metav1.ConditionTrue, 3)}, 2, true},
		{"ready is stale — spec changed since", []metav1.Condition{cond(conditionTypeReady, metav1.ConditionTrue, 1)}, 2, false},
		{"not ready", []metav1.Condition{cond(conditionTypeReady, metav1.ConditionFalse, 2)}, 2, false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isReadyForGeneration(tc.conds, tc.generation); got != tc.want {
				t.Errorf("isReadyForGeneration() = %v, want %v", got, tc.want)
			}
		})
	}
}
