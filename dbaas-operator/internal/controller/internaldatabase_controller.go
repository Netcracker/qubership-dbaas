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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=internaldatabases,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=internaldatabases/status,verbs=get;update;patch

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
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
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

// pollRequeueAfter is the interval between polls of an in-progress async operation.
const pollRequeueAfter = 5 * time.Second

// InternalDatabaseReconciler reconciles InternalDatabase objects.
//
// The reconcile loop has two branches:
//   - SUBMIT: no pending trackingId → validate, build payload, call POST /apply.
//     HTTP 200 → Succeeded (synchronous). HTTP 202 → store trackingId, requeue for polling.
//   - POLL: pending trackingId → call GET /operation/{id}/status.
//     COMPLETED → Succeeded. FAILED/TERMINATED → InvalidConfiguration. IN_PROGRESS → requeue.
type InternalDatabaseReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver

	// asyncStartMu guards asyncStartTimes.
	asyncStartMu sync.Mutex
	// asyncStartTimes records the wall-clock time when an async operation was
	// submitted (HTTP 202), keyed by "namespace/name". Consumed when the
	// operation reaches a terminal state (COMPLETED, FAILED, TERMINATED).
	asyncStartTimes map[string]time.Time

	bindingTriggerTracker
}

func (r *InternalDatabaseReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	// requestID is stored in ctx; sub-methods retrieve it via requestIDFromContext.
	ctx, _ = initReconcileContext(ctx)

	dd := &dbaasv1.InternalDatabase{}
	if err := r.Get(ctx, req.NamespacedName, dd); err != nil {
		if apierrors.IsNotFound(err) {
			key := req.Namespace + "/" + req.Name
			r.clearBindingTrigger(key)
			r.clearAsyncStart(key)
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := req.Namespace + "/" + req.Name
	bindingTriggered := r.consumeBindingTrigger(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, dd.Namespace, dd.Name, "InternalDatabase")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(key)
		r.clearAsyncStart(key)
		return result, nil
	}

	original := dd.DeepCopy()

	// Stamp observedGeneration only when reaching a terminal state: Ready=True
	// (provisioned) or Stalled=True (permanent error). A pending async operation
	// or a transient error leaves both false — don't stamp.
	defer func() {
		patchStatusOnExit(ctx, r.Status(), dd, original, &retErr,
			func(dd *dbaasv1.InternalDatabase, _ error) bool {
				return isTerminal(dd.Status.Conditions)
			},
			"InternalDatabase")
	}()

	// If spec changed while an async operation was in progress, discard the
	// stale trackingId and start fresh.
	if dd.Status.TrackingID != "" &&
		dd.Status.PendingOperationGeneration != dd.Generation {
		log.InfoC(ctx, "spec changed while polling, clearing stale trackingId pendingGen=%v currentGen=%v trackingId=%v",
			dd.Status.PendingOperationGeneration, dd.Generation, dd.Status.TrackingID)
		dd.Status.TrackingID = ""
		dd.Status.PendingOperationGeneration = 0
		r.clearAsyncStart(key)
	}

	trigger := triggerSpecChange
	if bindingTriggered {
		trigger = triggerNamespaceBindingChange
	} else if dd.Status.TrackingID != "" {
		trigger = triggerPolling
	}
	recordReconcileTrigger(controllerIDB, trigger)

	if dd.Status.TrackingID != "" {
		return r.reconcilePoll(ctx, dd)
	}
	return r.reconcileSubmit(ctx, dd)
}

// reconcileSubmit handles the SUBMIT branch: pre-flight validation + POST /apply.
func (r *InternalDatabaseReconciler) reconcileSubmit(ctx context.Context, dd *dbaasv1.InternalDatabase) (ctrl.Result, error) {
	requestID := requestIDFromContext(ctx)
	dd.Status.Phase = dbaasv1.PhaseProcessing

	if msg := validateInternalDatabaseSpec(dd); msg != "" {
		return invalidSpec(ctx, &dd.Status.Phase, &dd.Status.Conditions, dd.Generation, r.Recorder, dd, msg)
	}

	// ── Call aggregator ───────────────────────────────────────────────────────
	payload := r.buildPayload(dd)
	dd.Status.LastRequestID = requestID
	aggStart := time.Now()
	resp, err := r.Aggregator.ApplyConfig(ctx, payload)
	recordAggregatorCall(controllerIDB, operationApplyConfig, aggStart, err)
	if err != nil {
		log.ErrorC(ctx, "failed to apply InternalDatabase to dbaas-aggregator: %v", err)
		return handleAggregatorError(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, r.Recorder, dd, err, requestID)
	}

	if resp.TrackingID != "" {
		// HTTP 202 Accepted — async operation started.
		log.InfoC(ctx, "database provisioning started asynchronously. trackingId = %v, microserviceName = %v", resp.TrackingID, dd.Spec.Classifier.MicroserviceName)
		ddKey := dd.Namespace + "/" + dd.Name
		r.asyncStartMu.Lock()
		if r.asyncStartTimes == nil {
			r.asyncStartTimes = make(map[string]time.Time)
		}
		r.asyncStartTimes[ddKey] = time.Now()
		r.asyncStartMu.Unlock()
		markProvisioningStarted(dd, resp.TrackingID)
		r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonProvisioningStarted,
			"database provisioning started asynchronously (trackingId=%s, requestId=%s)",
			resp.TrackingID, requestID)
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
	}

	// HTTP 200 OK — synchronous completion.
	log.InfoC(ctx, "database provisioned synchronously. microserviceName = %v", dd.Spec.Classifier.MicroserviceName)
	if err := r.materializeTenantDatabaseIfPinned(ctx, dd); err != nil {
		log.ErrorC(ctx, "failed to materialize pinned tenant database: %v", err)
		return handleAggregatorError(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, r.Recorder, dd, err, requestID)
	}
	markSucceeded(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, EventReasonDatabaseProvisioned)
	r.Recorder.Eventf(dd, corev1.EventTypeNormal, EventReasonDatabaseProvisioned,
		"database provisioned synchronously (microserviceName=%s)",
		dd.Spec.Classifier.MicroserviceName)
	return ctrl.Result{}, nil
}

// reconcilePoll handles the POLL branch: GET /operation/{trackingId}/status.
func (r *InternalDatabaseReconciler) reconcilePoll(ctx context.Context, dd *dbaasv1.InternalDatabase) (ctrl.Result, error) {
	requestID := requestIDFromContext(ctx)

	trackingID := dd.Status.TrackingID
	log.DebugC(ctx, "polling operation status trackingId=%v", trackingID)

	dd.Status.Phase = dbaasv1.PhaseWaitingForDependency
	dd.Status.LastRequestID = requestID

	aggStart := time.Now()
	resp, err := r.Aggregator.GetOperationStatus(ctx, trackingID)
	recordAggregatorCall(controllerIDB, operationPollStatus, aggStart, err)
	if err != nil {
		return r.handlePollError(ctx, dd, trackingID, requestID, err)
	}

	return r.handlePollResponse(ctx, dd, trackingID, requestID, resp)
}

// buildPayload assembles the DeclarativePayload for POST /api/declarations/v1/apply.
// kind/subKind are hardcoded to what the aggregator expects.
// microserviceName goes into metadata; the entire spec is forwarded as-is.
func (r *InternalDatabaseReconciler) buildPayload(dd *dbaasv1.InternalDatabase) *aggregatorclient.DeclarativePayload {
	return &aggregatorclient.DeclarativePayload{
		APIVersion: apiVersionV1,
		Kind:       "DBaaS",
		SubKind:    "DatabaseDeclaration",
		Metadata: aggregatorclient.DeclarativeMeta{
			Name:             dd.Name,
			Namespace:        dd.Namespace,
			MicroserviceName: dd.Spec.Classifier.MicroserviceName,
		},
		Spec: toWireSpec(dd.Spec, dd.Namespace),
	}
}

// materializeTenantDatabaseIfPinned eagerly creates the concrete {scope=tenant, tenantId} database for
// a tenant-scoped InternalDatabase that pins an explicit tenantId. The declarative apply only registers
// a tenant-agnostic template: the aggregator drops tenantId from a tenant declaration and materializes
// per-tenant databases only for tenants that already exist, so a freshly declared, never-yet-connected
// tenant has no database. Without this, a DatabaseSecretClaim for {scope=tenant, tenantId} does a
// read-only get-by-classifier and waits forever (DatabaseNotFound). Calling the get-or-create database
// API materializes the database exactly as the first runtime tenant connection would, after which the
// claim resolves. No-op for service scope or a tenant declaration without a pinned tenantId.
func (r *InternalDatabaseReconciler) materializeTenantDatabaseIfPinned(ctx context.Context, dd *dbaasv1.InternalDatabase) error {
	if !strings.EqualFold(dd.Spec.Classifier.Scope, "tenant") || dd.Spec.Classifier.TenantId == "" {
		return nil
	}
	req := &aggregatorclient.CreateDatabaseRequest{
		Classifier:    dbaasv1.ClassifierFlatMap(dbaasv1.EffectiveClassifier(dd.Spec.Classifier, dd.Namespace)),
		Type:          dd.Spec.Type,
		OriginService: dd.Spec.Classifier.MicroserviceName,
	}
	log.InfoC(ctx, "materializing pinned tenant database tenantId=%v microserviceName=%v",
		dd.Spec.Classifier.TenantId, dd.Spec.Classifier.MicroserviceName)
	start := time.Now()
	err := r.Aggregator.CreateDatabase(ctx, dd.Namespace, req)
	recordAggregatorCall(controllerIDB, operationCreateDatabase, start, err)
	return err
}

// toWireSpec converts a InternalDatabaseSpec (CRD shape) into the wire format
// expected by dbaas-aggregator. namespace is the owning CR's metadata.namespace,
// used to default classifier.namespace when omitted.
//
// Mapping summary:
//   - spec.classifier         → classifierConfig.classifier (SortedMap<String,Object>)
//   - spec.classifier.customKeys → classifier["customKeys"] (nested map inside the flat map)
//   - initialInstantiation.sourceClassifier → initialInstantiation.sourceClassifier (same flat-map shape)
//
// The main classifier's namespace is defaulted to the CR's namespace (the
// aggregator requires it; the controller already validates that a non-empty
// value equals metadata.namespace). sourceClassifier is left as-is — a clone
// source may legitimately live in a different namespace, and no equality
// constraint is enforced on it.
func toWireSpec(spec dbaasv1.InternalDatabaseSpec, namespace string) aggregatorclient.DatabaseDeclarationSpecWire {
	wire := aggregatorclient.DatabaseDeclarationSpecWire{
		ClassifierConfig: aggregatorclient.ClassifierConfigWire{
			Classifier: dbaasv1.ClassifierFlatMap(dbaasv1.EffectiveClassifier(spec.Classifier, namespace)),
		},
		Type:       spec.Type,
		Lazy:       spec.Lazy,
		Settings:   spec.Settings,
		NamePrefix: spec.NamePrefix,
	}
	if spec.VersioningConfig != nil {
		wire.VersioningConfig = &aggregatorclient.VersioningConfigWire{
			Approach: spec.VersioningConfig.Approach,
		}
	}
	if spec.InitialInstantiation != nil {
		ii := &aggregatorclient.InitialInstantiationWire{
			Approach: spec.InitialInstantiation.Approach,
		}
		if spec.InitialInstantiation.SourceClassifier != nil {
			ii.SourceClassifier = dbaasv1.ClassifierFlatMap(*spec.InitialInstantiation.SourceClassifier)
		}
		wire.InitialInstantiation = ii
	}
	return wire
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

func validateInternalDatabaseSpec(dd *dbaasv1.InternalDatabase) string {
	// CRD enforces: classifier.required, classifier.microserviceName/scope required+minLength,
	// type required+minLength. Controller handles cross-field constraints only.

	// If classifier.namespace is set it must match the CR's own namespace.
	// A mismatch is a permanent misconfiguration — no retry.
	if ns := dd.Spec.Classifier.Namespace; ns != "" && ns != dd.Namespace {
		return fmt.Sprintf(
			"spec.classifier.namespace %q must match metadata.namespace %q",
			ns, dd.Namespace)
	}

	// extraKeys must not shadow the typed classifier fields — a collision is a
	// spec mistake (the typed field would win and the extraKey be dropped).
	if reserved := dbaasv1.ReservedExtraKeys(dd.Spec.Classifier); len(reserved) > 0 {
		return fmt.Sprintf(
			"spec.classifier.extraKeys must not contain the reserved keys %v — they are owned by the typed classifier fields",
			reserved)
	}

	if dd.Spec.Lazy &&
		dd.Spec.InitialInstantiation != nil &&
		dd.Spec.InitialInstantiation.Approach == "clone" {
		return "spec: lazy=true is prohibited when initialInstantiation.approach=clone"
	}

	if dd.Spec.InitialInstantiation != nil {
		if dd.Spec.InitialInstantiation.Approach == "clone" &&
			dd.Spec.InitialInstantiation.SourceClassifier == nil {
			return "spec: initialInstantiation.sourceClassifier is required when approach=clone"
		}

		if dd.Spec.InitialInstantiation.SourceClassifier != nil &&
			dd.Spec.InitialInstantiation.SourceClassifier.MicroserviceName !=
				dd.Spec.Classifier.MicroserviceName {
			return "spec: initialInstantiation.sourceClassifier.microserviceName must match classifier.microserviceName"
		}

		if sc := dd.Spec.InitialInstantiation.SourceClassifier; sc != nil {
			if reserved := dbaasv1.ReservedExtraKeys(*sc); len(reserved) > 0 {
				return fmt.Sprintf(
					"spec.initialInstantiation.sourceClassifier.extraKeys must not contain the reserved keys %v — they are owned by the typed classifier fields",
					reserved)
			}
		}
	}

	return ""
}

func markProvisioningStarted(dd *dbaasv1.InternalDatabase, trackingID string) {
	dd.Status.TrackingID = trackingID
	dd.Status.PendingOperationGeneration = dd.Generation
	dd.Status.Phase = dbaasv1.PhaseWaitingForDependency
	setCondition(&dd.Status.Conditions, dd.Generation,
		conditionTypeReady, metav1.ConditionFalse, EventReasonProvisioningStarted,
		"database provisioning started asynchronously")
	setCondition(&dd.Status.Conditions, dd.Generation,
		conditionTypeStalled, metav1.ConditionFalse, EventReasonProvisioningStarted, stalledMsgTransient)
}

func clearPendingOperation(dd *dbaasv1.InternalDatabase) {
	dd.Status.TrackingID = ""
	dd.Status.PendingOperationGeneration = 0
}

// observeAsyncCompletion records the end-to-end async operation duration and
// consumes the in-memory start stamp. Safe to call even when no start stamp exists
// (e.g. operator restarted mid-operation — in that case duration is not recorded).
func (r *InternalDatabaseReconciler) observeAsyncCompletion(dd *dbaasv1.InternalDatabase, result string) {
	ddKey := dd.Namespace + "/" + dd.Name
	r.asyncStartMu.Lock()
	start, ok := r.asyncStartTimes[ddKey]
	if ok {
		delete(r.asyncStartTimes, ddKey)
	}
	r.asyncStartMu.Unlock()
	if ok {
		dbaasAsyncOperationDurationSeconds.WithLabelValues(result).Observe(time.Since(start).Seconds())
	}
}

// clearAsyncStart drops any pending async-operation start stamp for key.
func (r *InternalDatabaseReconciler) clearAsyncStart(key string) {
	r.asyncStartMu.Lock()
	defer r.asyncStartMu.Unlock()
	delete(r.asyncStartTimes, key)
}

func (r *InternalDatabaseReconciler) handlePollError(
	ctx context.Context,
	dd *dbaasv1.InternalDatabase,
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
			r.clearAsyncStart(dd.Namespace + "/" + dd.Name)
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

func (r *InternalDatabaseReconciler) handlePollResponse(
	ctx context.Context,
	dd *dbaasv1.InternalDatabase,
	trackingID string,
	requestID string,
	resp *aggregatorclient.DeclarativeResponse,
) (ctrl.Result, error) {
	switch resp.Status {
	case aggregatorclient.TaskStateCompleted:
		log.InfoC(ctx, "database provisioned. trackingId = %v, microserviceName = %v",
			trackingID, dd.Spec.Classifier.MicroserviceName)
		clearPendingOperation(dd)
		r.observeAsyncCompletion(dd, resultSuccess)
		if err := r.materializeTenantDatabaseIfPinned(ctx, dd); err != nil {
			log.ErrorC(ctx, "failed to materialize pinned tenant database: %v", err)
			return handleAggregatorError(&dd.Status.Phase, &dd.Status.Conditions, dd.Generation, r.Recorder, dd, err, requestID)
		}
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
		r.observeAsyncCompletion(dd, asyncResultFailed)
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
		r.observeAsyncCompletion(dd, asyncResultTerminated)
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
func (r *InternalDatabaseReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.InternalDatabase{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		// Re-enqueue all InternalDatabases in a namespace when its NamespaceBinding
		// is created or updated, so existing CRs are reconciled without waiting for
		// a spec change.
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		WithOptions(opts).
		Named("internaldatabase").
		Complete(r)
}

// enqueueForBinding maps an NamespaceBinding event to reconcile requests for
// all InternalDatabases that live in the same namespace.
func (r *InternalDatabaseReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	return enqueueForBindingList(ctx, r.Client, &dbaasv1.InternalDatabaseList{}, obj.GetNamespace(),
		func(o client.Object) { r.stampBindingTrigger(o.GetNamespace() + "/" + o.GetName()) })
}
