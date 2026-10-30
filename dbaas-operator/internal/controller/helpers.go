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
	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
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
	if dbaasv1.IsAssignedTo(resourceOperatorNamespace, myNamespace) {
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

// remoteError is implemented by the typed HTTP errors of every remote service
// the operator calls (*AggregatorError, *AdapterError). Both classify a status
// code the same way, so handleRemoteError produces the same phase, Stalled
// condition, and retry behavior whichever service answered.
type remoteError interface {
	error
	IsAuthError() bool
	IsSpecRejection() bool
	UserMessage() string
}

// remoteService describes how failures of one remote service appear in the
// status and in events. Only the names differ between services; the
// classification is shared.
type remoteService struct {
	// name opens every event message, e.g. "dbaas-aggregator".
	name string
	// credentials names whose credentials a 401 rejected.
	credentials string
	// rejectedReason is the reason for 400/403/409/410/422.
	rejectedReason string
	// errorReason is the reason for 5xx, other 4xx, and transport failures.
	errorReason string
	// as extracts the service's typed error from err.
	as func(err error) (remoteError, bool)
}

var aggregatorService = remoteService{
	name:           "dbaas-aggregator",
	credentials:    "operator credentials",
	rejectedReason: EventReasonAggregatorRejected,
	errorReason:    EventReasonAggregatorError,
	as: func(err error) (remoteError, bool) {
		var aggErr *aggregatorclient.AggregatorError
		if errors.As(err, &aggErr) {
			return aggErr, true
		}
		return nil, false
	},
}

var adapterService = remoteService{
	name:           "dbaas adapter",
	credentials:    "the credentials from the referenced Secret",
	rejectedReason: EventReasonAdapterRejected,
	errorReason:    EventReasonAdapterError,
	as: func(err error) (remoteError, bool) {
		var adapterErr *aggregatorclient.AdapterError
		if errors.As(err, &adapterErr) {
			return adapterErr, true
		}
		return nil, false
	},
}

// handleAggregatorError maps an aggregator failure to status, event, and retry
// behavior; see handleRemoteError for the classification.
func handleAggregatorError[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	err error,
	requestID string,
) (ctrl.Result, error) {
	return handleRemoteError(phase, conditions, generation, recorder, obj, err, requestID, aggregatorService)
}

// handleAdapterError maps a dbaas adapter failure to status, event, and retry
// behavior exactly like handleAggregatorError, with adapter reasons.
func handleAdapterError[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	err error,
	requestID string,
) (ctrl.Result, error) {
	return handleRemoteError(phase, conditions, generation, recorder, obj, err, requestID, adapterService)
}

// handleRemoteError maps a remote service failure to status, event, and retry behavior:
//   - invalid request context -> InvalidConfiguration (permanent, no retry)
//   - 401 → BackingOff with Unauthorized (transient, retry)
//   - 400/403/409/410/422 → InvalidConfiguration with svc.rejectedReason (permanent, no retry)
//   - 5xx/other 4xx/network → BackingOff with svc.errorReason (transient, retry)
func handleRemoteError[P ~string](
	phase *P,
	conditions *[]metav1.Condition,
	generation int64,
	recorder record.EventRecorder,
	obj runtime.Object,
	err error,
	requestID string,
	svc remoteService,
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

	remoteErr, ok := svc.as(err)
	if ok {
		switch {
		case remoteErr.IsAuthError():
			// 401 — credentials misconfigured; retry.
			markTransientFailure(phase, conditions, generation,
				EventReasonUnauthorized, remoteErr.UserMessage())
			recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonUnauthorized,
				"%s rejected %s (HTTP 401): %s (requestId=%s)",
				svc.name, svc.credentials, remoteErr.UserMessage(), requestID)
			return ctrl.Result{}, err

		case remoteErr.IsSpecRejection():
			// 400/403/409/410/422 — the service explicitly rejected the request.
			// Retrying the same payload will not help; wait for a spec change.
			markPermanentFailure(phase, conditions, generation,
				svc.rejectedReason, remoteErr.UserMessage())
			recorder.Eventf(obj, corev1.EventTypeWarning, svc.rejectedReason,
				"%s rejected request: %s (requestId=%s)",
				svc.name, remoteErr.UserMessage(), requestID)
			return ctrl.Result{}, nil
		}
	}

	// 5xx / other 4xx / network — transient; retry with backoff.
	errMsg := err.Error()
	if ok {
		errMsg = remoteErr.UserMessage()
	}
	markTransientFailure(phase, conditions, generation, svc.errorReason, errMsg)
	recorder.Eventf(obj, corev1.EventTypeWarning, svc.errorReason,
		"%s error: %s (requestId=%s)", svc.name, errMsg, requestID)
	return ctrl.Result{}, err
}

// isOlderClaimant returns true when a was created strictly before b. On equal
// creationTimestamps it falls back to lexical UID comparison so the result is
// stable across both peers (otherwise a tie would leave both CRs as "younger"
// and neither would lose). Controllers use it to resolve a claim that two CRs
// make on the same resource: the older claimant wins.
func isOlderClaimant(a, b metav1.Object) bool {
	aCreated, bCreated := a.GetCreationTimestamp(), b.GetCreationTimestamp()
	if aCreated.Before(&bCreated) {
		return true
	}
	if bCreated.Before(&aCreated) {
		return false
	}
	return string(a.GetUID()) < string(b.GetUID())
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
