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

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/requestcontext"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	apimeta "k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

var log = logging.GetLogger("dbaas-operator")

const (
	apiVersionV1 = "core.netcracker.com/v1"
)

// initReconcileContext seeds ctx with a fresh X-Request-Id and returns both
// the enriched context and the raw ID string (used in status fields and event messages).
func initReconcileContext(ctx context.Context) (context.Context, string) {
	return requestcontext.WithFreshRequestID(ctx)
}

// isEligibleForOperator reports whether this operator instance owns a resource.
// The check must run before status, finalizer, Secret, or aggregator mutations so
// multiple operator installations can watch the same CRDs without competing.
func isEligibleForOperator(
	ctx context.Context,
	resourceOperatorNamespace, myNamespace, namespace, name, kind string,
) bool {
	if resourceOperatorNamespace == myNamespace {
		return true
	}
	log.InfoC(ctx,
		"Skipping %s namespace=%s name=%s operatorNamespace=%s mine=%s",
		kind, namespace, name, resourceOperatorNamespace, myNamespace)
	return false
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

func markSucceeded[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	readyReason string,
) {
	*phase = P("Succeeded")
	setCondition(conditions, generation,
		conditionTypeReady, metav1.ConditionTrue, readyReason, "")
	setCondition(conditions, generation,
		conditionTypeStalled, metav1.ConditionFalse, ReasonSucceeded, "")
}

func markTransientFailure[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	readyReason, readyMessage string,
) {
	*phase = P("BackingOff")
	setCondition(conditions, generation,
		conditionTypeReady, metav1.ConditionFalse, readyReason, readyMessage)
	setCondition(conditions, generation,
		conditionTypeStalled, metav1.ConditionFalse, readyReason, stalledMsgTransient)
}

// invalidSpec sets InvalidConfiguration phase + conditions, emits a Warning event,
// and returns (no requeue) so the CR waits for a spec change.
// Shared by all controllers that perform pre-flight spec validation.
func invalidSpec[P ~string](
	ctx context.Context,
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	msg string,
) (ctrl.Result, error) {
	log.InfoC(ctx, "invalid spec reason=%v", msg)
	markPermanentFailure(phase, conditions, generation, EventReasonInvalidSpec, msg)
	recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonInvalidSpec, "%s", msg)
	return ctrl.Result{}, nil
}

// handleAggregatorError maps an aggregator failure to status, event, and retry behavior:
//   - invalid request context -> InvalidConfiguration (permanent, no retry)
//   - 401 → BackingOff (transient, retry)
//   - 400/403/409/410/422 → InvalidConfiguration (permanent, no retry)
//   - 5xx/network → BackingOff (transient, retry)
func handleAggregatorError[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	err error,
	requestID string,
) (ctrl.Result, error) {
	var requestContextErr *aggregatorclient.RequestContextError
	if errors.As(err, &requestContextErr) {
		markPermanentFailure(phase, conditions, generation,
			EventReasonInvalidRequestContext, requestContextErr.Error())
		recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonInvalidRequestContext,
			"operator request context is invalid: %s (requestId=%s)",
			requestContextErr.Error(), requestID)
		return ctrl.Result{}, nil
	}

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

func markPermanentFailure[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	readyReason, readyMessage string,
) {
	*phase = P("InvalidConfiguration")
	setCondition(conditions, generation,
		conditionTypeReady, metav1.ConditionFalse, readyReason, readyMessage)
	setCondition(conditions, generation,
		conditionTypeStalled, metav1.ConditionTrue, readyReason, stalledMsgPermanent)
}

func patchStatusOnExit[T interface {
	client.Object
	SetObservedGeneration(int64)
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

	patchErr := statusWriter.Patch(ctx, obj, client.MergeFrom(original))
	if patchErr == nil {
		return
	}
	if apierrors.IsNotFound(patchErr) {
		// The reconcile may have released the object's last finalizer, letting a
		// pending deletion complete before this deferred patch ran. A vanished
		// object has no status to report — not an error.
		log.InfoC(ctx, "skipping %v status patch: object is gone", objectType)
		return
	}
	log.ErrorC(ctx, "patch %v status: %v", objectType, patchErr)
	*retErr = errors.Join(*retErr, patchErr)
}

func setObservedGeneration[T interface {
	client.Object
	SetObservedGeneration(int64)
}](obj T) {
	obj.SetObservedGeneration(obj.GetGeneration())
}

// conditionTrueForGeneration reports whether the condition of the given type
// is True and was recorded for generation or newer. A True condition left over
// from an earlier generation does not count: conditions persist across
// reconciles, so after a spec change the controller must re-earn the condition
// for the new generation.
func conditionTrueForGeneration(conditions []metav1.Condition, condType string, generation int64) bool {
	c := apimeta.FindStatusCondition(conditions, condType)
	return c != nil &&
		c.Status == metav1.ConditionTrue &&
		c.ObservedGeneration >= generation
}

// isTerminal reports whether the controller has finished with the resource for
// the given generation: either it was processed successfully (Ready=True) or
// it hit a permanent error that will not be retried until the spec changes
// (Stalled=True), and the terminal condition was recorded for that generation
// or newer. The generation check is what makes the predicate safe as the
// shouldObserve gate in patchStatusOnExit: a reconcile that exits early
// without touching conditions (for example on a benign create/update race)
// still carries the previous generation's Ready=True, and without the check
// the exit patch would stamp status.observedGeneration for a spec it never
// finished processing. The former phase-based predicate was immune to this
// because phase was reset to Processing at the start of every reconcile.
func isTerminal(conditions []metav1.Condition, generation int64) bool {
	return conditionTrueForGeneration(conditions, conditionTypeReady, generation) ||
		conditionTrueForGeneration(conditions, conditionTypeStalled, generation)
}

// isReadyForGeneration reports whether Ready=True was recorded for generation
// or newer, so callers can tell "successfully reconciled the current spec"
// from "succeeded once, but the spec has changed since".
func isReadyForGeneration(conditions []metav1.Condition, generation int64) bool {
	return conditionTrueForGeneration(conditions, conditionTypeReady, generation)
}
