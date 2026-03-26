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

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/predicate"

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
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
}

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasedeclarations,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasedeclarations/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasedeclarations/finalizers,verbs=update
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *DatabaseDeclarationReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx = ctxmanager.InitContext(ctx, map[string]interface{}{
		"X-Request-Id": uuid.New().String(),
	})

	dd := &dbaasv1alpha1.DatabaseDeclaration{}
	if err := r.Get(ctx, req.NamespacedName, dd); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
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
	if dd.Status.TrackingId != "" &&
		dd.Status.PendingOperationGeneration != dd.Generation {
		log.InfoC(ctx, "spec changed while polling, clearing stale trackingId pendingGen=%v currentGen=%v trackingId=%v",
			dd.Status.PendingOperationGeneration, dd.Generation, dd.Status.TrackingId)
		dd.Status.TrackingId = ""
		dd.Status.PendingOperationGeneration = 0
	}

	if dd.Status.TrackingId != "" {
		return r.reconcilePoll(ctx, dd)
	}
	return r.reconcileSubmit(ctx, dd)
}

// reconcileSubmit handles the SUBMIT branch: pre-flight validation + POST /apply.
func (r *DatabaseDeclarationReconciler) reconcileSubmit(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration) (ctrl.Result, error) {
	dd.Status.Phase = dbaasv1alpha1.PhaseProcessing

	if msg := validateDatabaseDeclarationSpec(dd); msg != "" {
		return r.invalidSpec(ctx, dd, msg)
	}

	// ── Call aggregator ───────────────────────────────────────────────────────
	payload := r.buildPayload(dd)
	resp, err := r.Aggregator.ApplyConfig(ctx, payload)
	if err != nil {
		log.ErrorC(ctx, "failed to apply DatabaseDeclaration to dbaas-aggregator: %v", err)
		return r.handleApplyError(ctx, dd, err)
	}

	if resp.TrackingId != "" {
		// HTTP 202 Accepted — async operation started.
		log.InfoC(ctx, "database provisioning started asynchronously trackingId=%v microserviceName=%v",
			resp.TrackingId, dd.Spec.ClassifierConfig.Classifier.MicroserviceName)
		r.markProvisioningStarted(dd, resp.TrackingId)
		r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonProvisioningStarted,
			"database provisioning started asynchronously (trackingId=%s)", resp.TrackingId)
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
	}

	// HTTP 200 OK — synchronous completion.
	log.InfoC(ctx, "database provisioned synchronously microserviceName=%v",
		dd.Spec.ClassifierConfig.Classifier.MicroserviceName)
	markSucceeded(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, EventReasonDatabaseProvisioned)
	r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonDatabaseProvisioned,
		"database provisioned synchronously (microserviceName=%s)",
		dd.Spec.ClassifierConfig.Classifier.MicroserviceName)
	return ctrl.Result{}, nil
}

// reconcilePoll handles the POLL branch: GET /operation/{trackingId}/status.
func (r *DatabaseDeclarationReconciler) reconcilePoll(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration) (ctrl.Result, error) {
	trackingID := dd.Status.TrackingId
	log.DebugC(ctx, "polling operation status trackingId=%v", trackingID)

	dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency

	resp, err := r.Aggregator.GetOperationStatus(ctx, trackingID)
	if err != nil {
		return r.handlePollError(ctx, dd, trackingID, err)
	}

	return r.handlePollResponse(ctx, dd, trackingID, resp)
}

// handleApplyError maps an error from ApplyConfig to the appropriate phase/conditions.
func (r *DatabaseDeclarationReconciler) handleApplyError(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration, err error) (ctrl.Result, error) {
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) {
		switch {
		case aggErr.IsAuthError():
			// 401 — credentials misconfigured; retry.
			markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
				EventReasonUnauthorized, aggErr.UserMessage())
			r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonUnauthorized,
				"dbaas-aggregator rejected operator credentials (HTTP 401): %s", aggErr.UserMessage())
			return ctrl.Result{}, err

		case aggErr.IsSpecRejection():
			// 400/403/409/410/422 — aggregator explicitly rejected the spec.
			// Retrying the same payload will not help; wait for a spec change.
			markPermanentFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
				EventReasonAggregatorRejected, aggErr.UserMessage())
			r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"dbaas-aggregator rejected request: %s", aggErr.UserMessage())
			return ctrl.Result{}, nil
		}
	}

	// 5xx / network — transient; retry with backoff.
	errMsg := err.Error()
	if aggErr != nil {
		errMsg = aggErr.UserMessage()
	}
	markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
		EventReasonAggregatorError, errMsg)
	r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorError,
		"dbaas-aggregator error: %s", errMsg)
	return ctrl.Result{}, err
}

// invalidSpec sets InvalidConfiguration phase + conditions, emits a Warning event,
// and returns (no requeue) so the CR waits for a spec change.
func (r *DatabaseDeclarationReconciler) invalidSpec(ctx context.Context, dd *dbaasv1alpha1.DatabaseDeclaration, msg string) (ctrl.Result, error) {
	log.InfoC(ctx, "invalid spec reason=%v", msg)
	markPermanentFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
		EventReasonInvalidSpec, msg)
	r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonInvalidSpec, msg)
	return ctrl.Result{}, nil
}

// buildPayload assembles the DeclarativePayload for POST /api/declarations/v1/apply.
// kind/subKind are hardcoded to what the aggregator expects.
// microserviceName goes into metadata; the entire spec is forwarded as-is.
func (r *DatabaseDeclarationReconciler) buildPayload(dd *dbaasv1alpha1.DatabaseDeclaration) *aggregatorclient.DeclarativePayload {
	return &aggregatorclient.DeclarativePayload{
		APIVersion: "core.netcracker.com/v1",
		Kind:       "DBaaS",
		SubKind:    "DatabaseDeclaration",
		Metadata: aggregatorclient.DeclarativeMeta{
			Name:             dd.Name,
			Namespace:        dd.Namespace,
			MicroserviceName: dd.Spec.ClassifierConfig.Classifier.MicroserviceName,
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
	// CRD enforces: classifierConfig.required, classifier.microserviceName/scope required+minLength,
	// type required+minLength. Controller handles cross-field constraints only.
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
			dd.Spec.ClassifierConfig.Classifier.MicroserviceName {
		return "spec: initialInstantiation.sourceClassifier.microserviceName must match" +
			" classifierConfig.classifier.microserviceName"
	}

	return ""
}

func (r *DatabaseDeclarationReconciler) markProvisioningStarted(
	dd *dbaasv1alpha1.DatabaseDeclaration,
	trackingID string,
) {
	dd.Status.TrackingId = trackingID
	dd.Status.PendingOperationGeneration = dd.Generation
	dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
	setCondition(&dd.Status.Conditions, dd.Generation,
		conditionTypeReady, metav1.ConditionFalse, EventReasonProvisioningStarted,
		fmt.Sprintf("database provisioning started asynchronously (trackingId=%s)", trackingID))
	setCondition(&dd.Status.Conditions, dd.Generation,
		conditionTypeStalled, metav1.ConditionFalse, EventReasonProvisioningStarted, stalledMsgTransient)
}

func (r *DatabaseDeclarationReconciler) clearPendingOperation(dd *dbaasv1alpha1.DatabaseDeclaration) {
	dd.Status.TrackingId = ""
	dd.Status.PendingOperationGeneration = 0
}

func (r *DatabaseDeclarationReconciler) handlePollError(
	ctx context.Context,
	dd *dbaasv1alpha1.DatabaseDeclaration,
	trackingID string,
	err error,
) (ctrl.Result, error) {
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) {
		if aggErr.IsAuthError() {
			// 401 — keep trackingId, retry with backoff.
			markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
				EventReasonUnauthorized, aggErr.UserMessage())
			r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonUnauthorized,
				"dbaas-aggregator rejected operator credentials during polling (HTTP 401): %s",
				aggErr.UserMessage())
			return ctrl.Result{}, err
		}

		if aggErr.StatusCode == http.StatusNotFound {
			// 404 — trackingId expired or never existed; clear it so the next
			// reconcile re-submits the operation.
			log.InfoC(ctx, "trackingId not found, will re-submit on next reconcile trackingId=%v", trackingID)
			r.clearPendingOperation(dd)
			markTransientFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
				EventReasonAggregatorError, "operation trackingId not found — will re-submit on next reconcile")
			r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorError,
				"operation trackingId not found (will re-submit)")
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
		"dbaas-aggregator error during polling: %s", errMsg)
	return ctrl.Result{}, err
}

func (r *DatabaseDeclarationReconciler) handlePollResponse(
	ctx context.Context,
	dd *dbaasv1alpha1.DatabaseDeclaration,
	trackingID string,
	resp *aggregatorclient.DeclarativeResponse,
) (ctrl.Result, error) {
	switch resp.Status {
	case aggregatorclient.TaskStateCompleted:
		log.InfoC(ctx, "database provisioned trackingId=%v microserviceName=%v",
			trackingID, dd.Spec.ClassifierConfig.Classifier.MicroserviceName)
		r.clearPendingOperation(dd)
		markSucceeded(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, EventReasonDatabaseProvisioned)
		r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonDatabaseProvisioned,
			"database provisioned (microserviceName=%s, trackingId=%s)",
			dd.Spec.ClassifierConfig.Classifier.MicroserviceName, trackingID)
		return ctrl.Result{}, nil

	case aggregatorclient.TaskStateFailed, aggregatorclient.TaskStateTerminated:
		reason := pollFailureReason(resp)
		log.InfoC(ctx, "database provisioning failed trackingId=%v status=%v reason=%v",
			trackingID, resp.Status, reason)
		r.clearPendingOperation(dd)
		markPermanentFailure(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation,
			EventReasonAggregatorRejected, reason)
		r.Recorder.Eventf(dd, corev1.EventTypeWarning, EventReasonAggregatorRejected,
			"database provisioning failed: %s", reason)
		return ctrl.Result{}, nil

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
		WithOptions(opts).
		Named("databasedeclaration").
		Complete(r)
}
