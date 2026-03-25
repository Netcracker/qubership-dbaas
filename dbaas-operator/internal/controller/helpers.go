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
	"context"
	"errors"

	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
)

// setCondition upserts a metav1.Condition in the given slice.
// LastTransitionTime is preserved when Status is unchanged, per Kubernetes API
// conventions. A change in Reason or Message at the same Status does not reset
// the transition time.
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
			if existing.Status == status {
				// Status unchanged: preserve the transition time per Kubernetes API
				// conventions (LastTransitionTime reflects Status changes only).
				cond.LastTransitionTime = existing.LastTransitionTime
			}
			(*conditions)[i] = cond
			return
		}
	}
	*conditions = append(*conditions, cond)
}

func markSucceeded(
	phase *dbaasv1alpha1.Phase,
	conditions *[]metav1.Condition,
	generation int64,
	readyReason string,
) {
	*phase = dbaasv1alpha1.PhaseSucceeded
	setCondition(conditions, generation,
		conditionTypeReady, metav1.ConditionTrue, readyReason, "")
	setCondition(conditions, generation,
		conditionTypeStalled, metav1.ConditionFalse, ReasonSucceeded, "")
}

func markTransientFailure(
	phase *dbaasv1alpha1.Phase,
	conditions *[]metav1.Condition,
	generation int64,
	readyReason, readyMessage string,
) {
	*phase = dbaasv1alpha1.PhaseBackingOff
	setCondition(conditions, generation,
		conditionTypeReady, metav1.ConditionFalse, readyReason, readyMessage)
	setCondition(conditions, generation,
		conditionTypeStalled, metav1.ConditionFalse, readyReason, stalledMsgTransient)
}

func markPermanentFailure(
	phase *dbaasv1alpha1.Phase,
	conditions *[]metav1.Condition,
	generation int64,
	readyReason, readyMessage string,
) {
	*phase = dbaasv1alpha1.PhaseInvalidConfiguration
	setCondition(conditions, generation,
		conditionTypeReady, metav1.ConditionFalse, readyReason, readyMessage)
	setCondition(conditions, generation,
		conditionTypeStalled, metav1.ConditionTrue, readyReason, stalledMsgPermanent)
}

func patchStatusOnExit[T interface {
	client.Object
	dbaasv1alpha1.ObservedGenerationSetter
}](
	ctx context.Context,
	statusWriter client.StatusWriter,
	obj T,
	original T,
	retErr *error,
	shouldObserve func(T, error) bool,
	objectType string,
) {
	if shouldObserve(obj, *retErr) {
		setObservedGeneration(obj)
	}

	if patchErr := statusWriter.Patch(ctx, obj, client.MergeFrom(original)); patchErr != nil {
		logf.FromContext(ctx).Error(patchErr, "patch "+objectType+" status")
		*retErr = errors.Join(*retErr, patchErr)
	}
}

func setObservedGeneration[T interface {
	client.Object
	dbaasv1alpha1.ObservedGenerationSetter
}](obj T) {
	obj.SetObservedGeneration(obj.GetGeneration())
}
