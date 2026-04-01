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

	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

var log = logging.GetLogger("dbaas-operator")

const (
	apiVersionV1 = "core.netcracker.com/v1"
	xRequestID   = "X-Request-Id"
)

// requestIDFromContext extracts the X-Request-Id string from ctx.
// Raising a panic if it can't fetch it
func requestIDFromContext(ctx context.Context) string {
	xrid, err := xrequestid.Of(ctx)
	if err != nil {
		log.ErrorC(ctx, "failed to retrieve request ID from context: %v", err)
		panic(err)
	}
	return xrid.GetRequestId()
}

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

// invalidSpec sets InvalidConfiguration phase + conditions, emits a Warning event,
// and returns (no requeue) so the CR waits for a spec change.
// Shared by all controllers that perform pre-flight spec validation.
func invalidSpec(
	ctx context.Context,
	phase *dbaasv1alpha1.Phase,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	msg string,
) (ctrl.Result, error) {
	log.InfoC(ctx, "invalid spec reason=%v", msg)
	markPermanentFailure(phase, conditions, generation, EventReasonInvalidSpec, msg)
	recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonInvalidSpec, msg)
	return ctrl.Result{}, nil
}

// handleAggregatorError maps a non-nil error from any aggregator call to the
// appropriate phase/conditions and emits a Kubernetes event.
// It is the shared implementation used by all three controllers.
//
//   - 401                   → BackingOff  (transient, requeue)
//   - 400/403/409/410/422   → InvalidConfiguration (permanent, no requeue)
//   - 5xx / network         → BackingOff  (transient, requeue)
func handleAggregatorError(
	phase *dbaasv1alpha1.Phase,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	err error,
	requestID string,
) (ctrl.Result, error) {
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) {
		switch {
		case aggErr.IsAuthError():
			// 401 — credentials misconfigured; retry.
			markTransientFailure(phase, conditions, generation,
				EventReasonUnauthorized, aggErr.UserMessage())
			recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonUnauthorized,
				"dbaas-aggregator rejected operator credentials (HTTP 401): %s (requestId=%s)",
				aggErr.UserMessage(), requestID)
			return ctrl.Result{}, err

		case aggErr.IsSpecRejection():
			// 400/403/409/410/422 — aggregator explicitly rejected the spec.
			// Retrying the same payload will not help; wait for a spec change.
			markPermanentFailure(phase, conditions, generation,
				EventReasonAggregatorRejected, aggErr.UserMessage())
			recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"dbaas-aggregator rejected request: %s (requestId=%s)",
				aggErr.UserMessage(), requestID)
			return ctrl.Result{}, nil
		}
	}

	// 5xx / network — transient; retry with backoff.
	errMsg := err.Error()
	if aggErr != nil {
		errMsg = aggErr.UserMessage()
	}
	markTransientFailure(phase, conditions, generation,
		EventReasonAggregatorError, errMsg)
	recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonAggregatorError,
		"dbaas-aggregator error: %s (requestId=%s)", errMsg, requestID)
	return ctrl.Result{}, err
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
		log.ErrorC(ctx, "patch %v status: %v", objectType, patchErr)
		*retErr = errors.Join(*retErr, patchErr)
	}
}

func setObservedGeneration[T interface {
	client.Object
	dbaasv1alpha1.ObservedGenerationSetter
}](obj T) {
	obj.SetObservedGeneration(obj.GetGeneration())
}
