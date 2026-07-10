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
	"sync"

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
	corev1 "k8s.io/api/core/v1"
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
	xRequestID   = "X-Request-Id"
)

// bindingTriggerTracker is a concurrency-safe set of "the next reconcile for this
// key was most likely caused by a NamespaceBinding change" stamps, keyed by
// namespace/name. Embed it by value into a reconciler to get stamp/consume/clear
// via method promotion; the zero value is ready to use. It is best-effort: a
// missed stamp only mis-classifies the reconcile trigger metric, never affects
// correctness.
type bindingTriggerTracker struct {
	mu     sync.Mutex
	stamps map[string]struct{}
}

// stampBindingTrigger records that the next reconcile for key was most likely
// caused by a NamespaceBinding change.
func (t *bindingTriggerTracker) stampBindingTrigger(key string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.stamps == nil {
		t.stamps = make(map[string]struct{})
	}
	t.stamps[key] = struct{}{}
}

// consumeBindingTrigger reports whether key had a pending stamp, removing it.
func (t *bindingTriggerTracker) consumeBindingTrigger(key string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, ok := t.stamps[key]; !ok {
		return false
	}
	delete(t.stamps, key)
	return true
}

// clearBindingTrigger drops any pending NamespaceBinding trigger stamp for key.
func (t *bindingTriggerTracker) clearBindingTrigger(key string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.stamps, key)
}

// enqueueForBindingList lists objects of list type L in namespace and returns one
// reconcile request per object — the shared body behind every controller's
// NamespaceBinding watch mapper. When stamp is non-nil it is invoked with each
// object before the request is appended (used to mark the resulting reconcile as
// binding-triggered). On a list error it logs and returns nil, so a transient
// failure simply drops this fan-out — the per-CR safety-net reconcile heals it.
func enqueueForBindingList[L client.ObjectList](
	ctx context.Context, c client.Client, list L, namespace string, stamp func(client.Object),
) []ctrl.Request {
	if err := c.List(ctx, list, client.InNamespace(namespace)); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list %T in %s: %v", list, namespace, err)
		return nil
	}
	objs, err := apimeta.ExtractList(list)
	if err != nil {
		log.ErrorC(ctx, "enqueueForBinding: extract %T items: %v", list, err)
		return nil
	}
	reqs := make([]ctrl.Request, 0, len(objs))
	for _, ro := range objs {
		o, ok := ro.(client.Object)
		if !ok {
			continue
		}
		if stamp != nil {
			stamp(o)
		}
		reqs = append(reqs, ctrl.Request{NamespacedName: client.ObjectKeyFromObject(o)})
	}
	return reqs
}

// requestIDFromContext returns the reconcile request ID from ctx.
func requestIDFromContext(ctx context.Context) string {
	xrid, err := xrequestid.Of(ctx)
	if err != nil {
		log.ErrorC(ctx, "failed to retrieve request ID from context: %v", err)
		panic(fmt.Sprintf("requestIDFromContext: context not initialized, error: %v", err))
	}
	return xrid.GetRequestId()
}

// initReconcileContext seeds ctx with a fresh X-Request-Id and returns both
// the enriched context and the raw ID string (used in status fields and event messages).
func initReconcileContext(ctx context.Context) (context.Context, string) {
	id := uuid.New().String()
	return ctxmanager.InitContext(ctx, map[string]any{xRequestID: id}), id
}

// checkOwnership returns whether reconciliation should proceed for namespace.
// Unknown ownership requeues quickly, Unbound requeues slowly as a safety net,
// and Foreign returns without requeue.
func checkOwnership(ctx context.Context, resolver *ownership.OwnershipResolver, namespace, name, kind string) (bool, ctrl.Result, error) {
	mine, err := resolver.IsMyNamespace(ctx, namespace)
	if err != nil {
		return false, ctrl.Result{}, err
	}
	if mine {
		return true, ctrl.Result{}, nil
	}
	switch resolver.GetState(namespace) {
	case ownership.Unknown:
		log.InfoC(ctx, "no NamespaceBinding for %s %s/%s yet, will retry in %s", kind, namespace, name, ownershipPollInterval)
		return false, ctrl.Result{RequeueAfter: ownershipPollInterval}, nil
	case ownership.Unbound:
		log.InfoC(ctx, "namespace %s unbound for %s %s, will retry in %s", namespace, kind, name, ownershipUnboundRetryInterval)
		return false, ctrl.Result{RequeueAfter: ownershipUnboundRetryInterval}, nil
	default:
		log.InfoC(ctx, "skipping %s %s/%s: namespace not owned by this operator", kind, namespace, name)
		return false, ctrl.Result{}, nil
	}
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
	recorder.Eventf(obj, corev1.EventTypeWarning, EventReasonInvalidSpec, msg)
	return ctrl.Result{}, nil
}

// handleAggregatorError maps an aggregator call failure to phase, conditions,
// event, and retry behavior for controllers that call dbaas-aggregator.
func handleAggregatorError[P ~string](
	phase *P,
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

	if patchErr := statusWriter.Patch(ctx, obj, client.MergeFrom(original)); patchErr != nil {
		log.ErrorC(ctx, "patch %v status: %v", objectType, patchErr)
		*retErr = errors.Join(*retErr, patchErr)
	}
}

func setObservedGeneration[T interface {
	client.Object
	SetObservedGeneration(int64)
}](obj T) {
	obj.SetObservedGeneration(obj.GetGeneration())
}
