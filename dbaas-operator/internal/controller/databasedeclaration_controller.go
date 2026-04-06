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
	"fmt"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

// pollRequeueAfter is the interval between polls of an in-progress async operation.
const pollRequeueAfter = 5 * time.Second

// DatabaseDeclarationReconciler reconciles DatabaseDeclaration objects.
//
// The reconcile loop has two branches:
//   - SUBMIT: no pending trackingId → validate, build payload, call POST /apply.
//     HTTP 200 → Succeeded (synchronous). HTTP 202 → store trackingId, requeue for polling.
//   - POLL: pending trackingId → call GET /operation/{id}/status.
//     COMPLETED → Succeeded. FAILED/TERMINATED → InvalidConfiguration. IN_PROGRESS → requeue.
type DatabaseDeclarationReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver
}

func (r *DatabaseDeclarationReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	requestID := uuid.New().String()
	ctx = ctxmanager.InitContext(ctx, map[string]interface{}{
		xRequestID: requestID,
	})

	dd := &dbaasv1alpha1.DatabaseDeclaration{}
	if err := r.Get(ctx, req.NamespacedName, dd); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// ── Ownership check ───────────────────────────────────────────────────────
	if mine, err := r.Ownership.IsMyNamespace(ctx, dd.Namespace); err != nil {
		return ctrl.Result{}, err
	} else if !mine {
		log.InfoC(ctx, "skipping DatabaseDeclaration %s/%s: namespace not owned by this operator", dd.Namespace, dd.Name)
		return ctrl.Result{}, nil
	}

	original := dd.DeepCopy()

	// Stamp observedGeneration only when reaching a terminal state.
	// WaitingForDependency/BackingOff are not terminal — don't stamp.
	defer func() {
		patchStatusOnExit(ctx, r.Status(), dd, original, &retErr,
			func(dd *dbaasv1alpha1.DatabaseDeclaration, _ error) bool {
				return dd.Status.Phase == dbaasv1alpha1.PhaseSucceeded ||
					dd.Status.Phase == dbaasv1alpha1.PhaseInvalidConfiguration
			},
			"DatabaseDeclaration")
	}()

	// If spec changed while an async operation was in progress, discard the
	// stale trackingId and start fresh.
	if dd.Status.TrackingID != "" &&
		dd.Status.PendingOperationGeneration != dd.Generation {
		log.InfoC(ctx, "spec changed while polling, clearing stale trackingId pendingGen=%v currentGen=%v trackingId=%v",
			dd.Status.PendingOperationGeneration, dd.Generation, dd.Status.TrackingID)
		dd.Status.TrackingID = ""
		dd.Status.PendingOperationGeneration = 0
	}

	if dd.Status.TrackingID != "" {
		return r.reconcilePoll(ctx, dd)
	}
	return r.reconcileSubmit(ctx, dd)
}

// reconcileSubmit handles the SUBMIT branch: pre-flight validation + POST /apply.
func (r *DatabaseDeclarationReconciler) reconcileSubmit(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration) (ctrl.Result, error) {
	requestID := requestIDFromContext(ctx)
	dd.Status.Phase = dbaasv1alpha1.PhaseProcessing

	if msg := validateDatabaseDeclarationSpec(dd); msg != "" {
		return r.invalidSpec(ctx, dd, msg)
	}

	// ── Call aggregator ───────────────────────────────────────────────────────
	payload := r.buildPayload(dd)
	dd.Status.LastRequestID = requestID
	resp, err := r.Aggregator.ApplyConfig(ctx, payload)
	if err != nil {
		log.ErrorC(ctx, "failed to apply DatabaseDeclaration to dbaas-aggregator: %v", err)
		return handleAggregatorError(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, r.Recorder, dd, err, requestID)
	}

	if resp.TrackingID != "" {
		// HTTP 202 Accepted — async operation started.
		log.Info("database provisioning started asynchronously. trackingId = %v, microserviceName = %v", resp.TrackingID, dd.Spec.Classifier.MicroserviceName)
		markProvisioningStarted(dd, resp.TrackingID)
		r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonProvisioningStarted,
			"database provisioning started asynchronously (trackingId=%s, requestId=%s)",
			resp.TrackingID, requestID)
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
	}

	// HTTP 200 OK — synchronous completion.
	log.Info("database provisioned synchronously. microserviceName = %v", dd.Spec.Classifier.MicroserviceName)
	markSucceeded(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, EventReasonDatabaseProvisioned)
	r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonDatabaseProvisioned,
		"database provisioned synchronously (microserviceName=%s)",
		dd.Spec.Classifier.MicroserviceName)
	return ctrl.Result{}, nil
}

// reconcilePoll handles the POLL branch: GET /operation/{trackingId}/status.
func (r *DatabaseDeclarationReconciler) reconcilePoll(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration) (ctrl.Result, error) {
	requestID := requestIDFromContext(ctx)

	trackingID := dd.Status.TrackingID
	log.DebugC(ctx, "polling operation status trackingId=%v", trackingID)

	dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
	dd.Status.LastRequestID = requestID

	resp, err := r.Aggregator.GetOperationStatus(ctx, trackingID)
	if err != nil {
		return r.handlePollError(ctx, dd, trackingID, requestID, err)
	}

	return r.handlePollResponse(ctx, dd, trackingID, requestID, resp)
}

func (r *DatabaseDeclarationReconciler) invalidSpec(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration, msg string) (ctrl.Result, error) {
	return invalidSpec(ctx, &dd.Status.Phase, &dd.Status.Conditions, dd.Generation, r.Recorder, dd, msg)
}

// buildPayload assembles the DeclarativePayload for POST /api/declarations/v1/apply.
// kind/subKind are hardcoded to what the aggregator expects.
// microserviceName goes into metadata; the entire spec is forwarded as-is.
func (r *DatabaseDeclarationReconciler) buildPayload(dd *dbaasv1alpha1.DatabaseDeclaration) *aggregatorclient.DeclarativePayload {
	return &aggregatorclient.DeclarativePayload{
		APIVersion: apiVersionV1,
		Kind:       "DBaaS",
		SubKind:    "DatabaseDeclaration",
		Metadata: aggregatorclient.DeclarativeMeta{
			Name:             dd.Name,
			Namespace:        dd.Namespace,
			MicroserviceName: dd.Spec.Classifier.MicroserviceName,
		},
		Spec: dd.Spec,
	}
}

// aggregatorConditionDataBaseCreated is the condition type used by dbaas-aggregator
// to report the outcome of async database provisioning operations.
// This is an external wire-format contract with the aggregator Java service.
// See: DeclarativeResponse.conditions[].type in dbaas-aggregator's applyConfigs().
const aggregatorConditionDataBaseCreated = "DataBaseCreated"

// pollConditionText extracts the best human-readable text from a poll response.
// Priority:
//  1. DataBaseCreated condition message  (primary contract, prefer over reason)
//  2. DataBaseCreated condition reason
//  3. Any other condition's message
//  4. Any other condition's reason
//  5. fallback
//
// Checking message before reason accommodates aggregators that put the
// human-readable text in message and the machine-readable code in reason.
func pollConditionText(resp *aggregatorclient.DeclarativeResponse, fallback string) string {
	var otherMsg, otherReason string
	for _, c := range resp.Conditions {
		if c.Type == aggregatorConditionDataBaseCreated {
			if c.Message != "" {
				return c.Message
			}
			if c.Reason != "" {
				return c.Reason
			}
		} else {
			if otherMsg == "" && c.Message != "" {
				otherMsg = c.Message
			}
			if otherReason == "" && c.Reason != "" {
				otherReason = c.Reason
			}
		}
	}
	if otherMsg != "" {
		return otherMsg
	}
	if otherReason != "" {
		return otherReason
	}
	return fallback
}

func validateDatabaseDeclarationSpec(dd *dbaasv1alpha1.DatabaseDeclaration) string {
	// CRD enforces: classifier.required, classifier.microserviceName/scope required+minLength,
	// type required+minLength. Controller handles cross-field constraints only.

	// If classifier.namespace is set it must match the CR's own namespace.
	// A mismatch is a permanent misconfiguration — no retry.
	if ns := dd.Spec.Classifier.Namespace; ns != "" && ns != dd.Namespace {
		return fmt.Sprintf(
			"spec.classifier.namespace %q must match metadata.namespace %q",
			ns, dd.Namespace)
	}

	if dd.Spec.Lazy &&
		dd.Spec.InitialInstantiation != nil &&
		dd.Spec.InitialInstantiation.Approach == "clone" {
		return "spec: lazy=true is prohibited when initialInstantiation.approach=clone"
	}

	if dd.Spec.InitialInstantiation != nil &&
		dd.Spec.InitialInstantiation.Approach == "clone" &&
		dd.Spec.InitialInstantiation.SourceClassifier == nil {
		return "spec: initialInstantiation.sourceClassifier is required when approach=clone"
	}

	if dd.Spec.InitialInstantiation != nil &&
		dd.Spec.InitialInstantiation.SourceClassifier != nil &&
		dd.Spec.InitialInstantiation.SourceClassifier.MicroserviceName !=
			dd.Spec.Classifier.MicroserviceName {
		return "spec: initialInstantiation.sourceClassifier.microserviceName must match classifier.microserviceName"
	}

	return ""
}

func markProvisioningStarted(dd *dbaasv1alpha1.DatabaseDeclaration, trackingID string) {
	dd.Status.TrackingID = trackingID
	dd.Status.PendingOperationGeneration = dd.Generation
	dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
	setCondition(&dd.Status.Conditions, dd.Generation,
		conditionTypeReady, metav1.ConditionFalse, EventReasonProvisioningStarted,
		"database provisioning started asynchronously")
	setCondition(&dd.Status.Conditions, dd.Generation,
		conditionTypeStalled, metav1.ConditionFalse, EventReasonProvisioningStarted, stalledMsgTransient)
}

func clearPendingOperation(dd *dbaasv1alpha1.DatabaseDeclaration) {
	dd.Status.TrackingID = ""
	dd.Status.PendingOperationGeneration = 0
}

func (r *DatabaseDeclarationReconciler) handlePollError(
	ctx context.Context,
	dd *dbaasv1alpha1.DatabaseDeclaration,
	trackingID string,
	requestID string,
	err error,
) (ctrl.Result, error) {
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) {
		if aggErr.IsAuthError() {
			// 401 — keep trackingId, retry with backoff.
			markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
				EventReasonUnauthorized, aggErr.UserMessage())
			r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonUnauthorized,
				"dbaas-aggregator rejected operator credentials during polling (HTTP 401): %s (requestId=%s)",
				aggErr.UserMessage(), requestID)
			return ctrl.Result{}, err
		}

		if aggErr.StatusCode == http.StatusNotFound {
			// 404 — trackingId expired or never existed; clear it so the next
			// reconcile re-submits the operation.
			log.InfoC(ctx, "trackingId not found, will re-submit on next reconcile trackingId=%v", trackingID)
			clearPendingOperation(dd)
			markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
				EventReasonAggregatorError, "operation trackingId not found — will re-submit on next reconcile")
			r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorError,
				"operation trackingId not found (will re-submit) (requestId=%s)", requestID)
			return ctrl.Result{}, err
		}
	}

	// 5xx / network error — keep trackingId, retry with backoff.
	errMsg := err.Error()
	if aggErr != nil {
		errMsg = aggErr.UserMessage()
	}
	markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
		EventReasonAggregatorError, errMsg)
	r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorError,
		"dbaas-aggregator error during polling: %s (requestId=%s)", errMsg, requestID)
	return ctrl.Result{}, err
}

func (r *DatabaseDeclarationReconciler) handlePollResponse(
	ctx context.Context,
	dd *dbaasv1alpha1.DatabaseDeclaration,
	trackingID string,
	requestID string,
	resp *aggregatorclient.DeclarativeResponse,
) (ctrl.Result, error) {
	switch resp.Status {
	case aggregatorclient.TaskStateCompleted:
		log.Infof("database provisioned. trackingId = %v, microserviceName = %v",
			trackingID, dd.Spec.Classifier.MicroserviceName)
		clearPendingOperation(dd)
		markSucceeded(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, EventReasonDatabaseProvisioned)
		r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonDatabaseProvisioned,
			"database provisioned (microserviceName=%s, trackingId=%s)",
			dd.Spec.Classifier.MicroserviceName, trackingID)
		return ctrl.Result{}, nil

	case aggregatorclient.TaskStateFailed:
		reason := pollFailureReason(resp)
		log.InfoC(ctx, "database provisioning failed trackingId=%v status=%v reason=%v",
			trackingID, resp.Status, reason)
		clearPendingOperation(dd)
		markPermanentFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
			EventReasonAggregatorRejected, reason)
		r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorRejected,
			"database provisioning failed: %s (requestId=%s)", reason, requestID)
		return ctrl.Result{}, nil

	case aggregatorclient.TaskStateTerminated:
		// The operation was terminated mid-flight (e.g. aggregator pod restart or an
		// explicit admin call to POST /operation/{id}/terminate). This is NOT a spec
		// error — the same payload can succeed when resubmitted.
		// Clear the stale trackingID so the next reconcile enters the SUBMIT branch.
		log.InfoC(ctx, "provisioning was terminated, clearing trackingId for resubmit trackingId=%v", trackingID)
		clearPendingOperation(dd)
		markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
			EventReasonOperationTerminated, "provisioning was terminated by the aggregator, resubmitting")
		r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonOperationTerminated,
			"provisioning terminated (trackingId=%s, requestId=%s), resubmitting", trackingID, requestID)
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil

	default: // IN_PROGRESS, NOT_STARTED — keep polling
		log.DebugC(ctx, "provisioning still in progress status=%v trackingId=%v", resp.Status, trackingID)
		if msg := pollProgressMessage(resp); msg != "" {
			setCondition(&dd.Status.Conditions, dd.Generation,
				conditionTypeReady, metav1.ConditionFalse, EventReasonProvisioningStarted, msg)
		}
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
	}
}

// pollFailureReason extracts a human-readable failure message from the poll response.
func pollFailureReason(resp *aggregatorclient.DeclarativeResponse) string {
	return pollConditionText(resp, fmt.Sprintf("operation ended with status %s", resp.Status))
}

// pollProgressMessage extracts the current progress description from the poll response.
func pollProgressMessage(resp *aggregatorclient.DeclarativeResponse) string {
	return pollConditionText(resp, "")
}

// SetupWithManager sets up the controller with the Manager.
// GenerationChangedPredicate ensures reconcile fires only on spec changes,
// not on the controller's own status updates. Timer-based requeues (RequeueAfter)
// bypass the predicate and always trigger reconcile.
func (r *DatabaseDeclarationReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1alpha1.DatabaseDeclaration{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		// Re-enqueue all DatabaseDeclarations in a namespace when its OperatorBinding
		// is created or updated, so existing CRs are reconciled without waiting for
		// a spec change.
		Watches(&dbaasv1.OperatorBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		WithOptions(opts).
		Named("databasedeclaration").
		Complete(r)
}

// enqueueForBinding maps an OperatorBinding event to reconcile requests for
// all DatabaseDeclarations that live in the same namespace.
func (r *DatabaseDeclarationReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	list := &dbaasv1alpha1.DatabaseDeclarationList{}
	if err := r.List(ctx, list, client.InNamespace(obj.GetNamespace())); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list DatabaseDeclarations in %s: %v", obj.GetNamespace(), err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}
