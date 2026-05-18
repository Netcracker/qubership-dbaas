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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpolicies,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpolicies/status,verbs=get;update;patch

import (
	"context"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
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

// DbPolicyReconciler reconciles DbPolicy objects.
//
// On every reconcile it validates the spec, assembles a DeclarativePayload, calls
// POST /api/declarations/v1/apply on dbaas-aggregator, and updates the CR status.
// Key outcomes are also emitted as Kubernetes Events.
type DbPolicyReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver

	bindingTriggerMu     sync.Mutex
	bindingTriggerStamps map[string]struct{}
}

func (r *DbPolicyReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	dp := &dbaasv1.DbPolicy{}
	if err := r.Get(ctx, req.NamespacedName, dp); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(req.Namespace + "/" + req.Name)
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := req.Namespace + "/" + req.Name
	bindingTriggered := r.consumeBindingTrigger(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, dp.Namespace, dp.Name, "DbPolicy")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		return result, nil
	}

	trigger := triggerSpecChange
	if bindingTriggered {
		trigger = triggerNamespaceBindingChange
	}
	recordReconcileTrigger(controllerDP, trigger)

	// Snapshot for the status patch at the end of reconcile.
	original := dp.DeepCopy()

	// Always patch status on exit, even if reconcile fails.
	defer func() {
		patchStatusOnExit(ctx, r.Status(), dp, original, &retErr,
			func(_ *dbaasv1.DbPolicy, retErr error) bool { return retErr == nil },
			"DbPolicy")
	}()

	// Mark as Processing while we work.
	dp.Status.Phase = dbaasv1.PhaseProcessing

	// ── Pre-flight validation ─────────────────────────────────────────────────
	// Field-level constraints (microserviceName, services[].name/roles, policy[].type/defaultRole)
	// are enforced by CRD admission. Only the cross-field constraint below cannot be expressed in schema.

	if len(dp.Spec.Services) == 0 && len(dp.Spec.Policy) == 0 {
		return r.invalidSpec(ctx, dp, "spec: at least one of 'services' or 'policy' must be set")
	}

	// ── Call aggregator ───────────────────────────────────────────────────────

	payload := r.buildPayload(dp)
	dp.Status.LastRequestID = requestID
	aggStart := time.Now()
	_, aggErr := r.Aggregator.ApplyConfig(ctx, payload)
	recordAggregatorCall(controllerDP, operationApplyConfig, aggStart, aggErr)
	if aggErr != nil {
		log.ErrorC(ctx, "failed to apply DbPolicy to dbaas-aggregator: %v", aggErr)
		return handleAggregatorError(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation, r.Recorder, dp, aggErr, requestID)
	}

	log.InfoC(ctx, "DbPolicy applied successfully microserviceName=%v", dp.Spec.MicroserviceName)
	markSucceeded(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation, EventReasonPolicyApplied)
	r.Recorder.Eventf(dp, corev1.EventTypeNormal, EventReasonPolicyApplied,
		"policy applied to dbaas-aggregator (microserviceName=%s, requestId=%s)",
		dp.Spec.MicroserviceName, requestID)
	return ctrl.Result{}, nil
}

func (r *DbPolicyReconciler) invalidSpec(ctx context.Context, dp *dbaasv1.DbPolicy, msg string) (ctrl.Result, error) {
	return invalidSpec(ctx, &dp.Status.Phase, &dp.Status.Conditions, dp.Generation, r.Recorder, dp, msg)
}

// dbPolicyAggregatorSpec is the wire-format spec sent to dbaas-aggregator.
// MicroserviceName is intentionally excluded: it goes into Metadata, not Spec.
type dbPolicyAggregatorSpec struct {
	Services                 []dbaasv1.ServiceRole `json:"services,omitempty"`
	Policy                   []dbaasv1.PolicyRole  `json:"policy,omitempty"`
	DisableGlobalPermissions bool                  `json:"disableGlobalPermissions,omitempty"`
}

// buildPayload assembles the DeclarativePayload for POST /api/declarations/v1/apply.
// MicroserviceName goes into metadata (not into the spec that is forwarded to the aggregator).
func (r *DbPolicyReconciler) buildPayload(dp *dbaasv1.DbPolicy) *aggregatorclient.DeclarativePayload {
	return &aggregatorclient.DeclarativePayload{
		APIVersion: apiVersionV1,
		Kind:       "DBaaS",
		SubKind:    "DbPolicy",
		Metadata: aggregatorclient.DeclarativeMeta{
			Name:             dp.Name,
			Namespace:        dp.Namespace,
			MicroserviceName: dp.Spec.MicroserviceName,
		},
		Spec: dbPolicyAggregatorSpec{
			Services:                 dp.Spec.Services,
			Policy:                   dp.Spec.Policy,
			DisableGlobalPermissions: dp.Spec.DisableGlobalPermissions,
		},
	}
}

// SetupWithManager sets up the controller with the Manager.
// GenerationChangedPredicate ensures reconcile fires only on spec changes,
// not on the controller's own status updates.
func (r *DbPolicyReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DbPolicy{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		// Re-enqueue all DbPolicies in a namespace when its NamespaceBinding
		// is created or updated, so existing CRs are reconciled without waiting for
		// a spec change.
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		WithOptions(opts).
		Named("dbpolicy").
		Complete(r)
}

// enqueueForBinding maps an NamespaceBinding event to reconcile requests for
// all DbPolicies that live in the same namespace.
func (r *DbPolicyReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	list := &dbaasv1.DbPolicyList{}
	if err := r.List(ctx, list, client.InNamespace(obj.GetNamespace())); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list DbPolicies in %s: %v", obj.GetNamespace(), err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		r.stampBindingTrigger(list.Items[i].Namespace + "/" + list.Items[i].Name)
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}

func (r *DbPolicyReconciler) stampBindingTrigger(key string) {
	r.bindingTriggerMu.Lock()
	defer r.bindingTriggerMu.Unlock()
	if r.bindingTriggerStamps == nil {
		r.bindingTriggerStamps = make(map[string]struct{})
	}
	r.bindingTriggerStamps[key] = struct{}{}
}

func (r *DbPolicyReconciler) consumeBindingTrigger(key string) bool {
	r.bindingTriggerMu.Lock()
	defer r.bindingTriggerMu.Unlock()
	if _, ok := r.bindingTriggerStamps[key]; !ok {
		return false
	}
	delete(r.bindingTriggerStamps, key)
	return true
}

func (r *DbPolicyReconciler) clearBindingTrigger(key string) {
	r.bindingTriggerMu.Lock()
	defer r.bindingTriggerMu.Unlock()
	delete(r.bindingTriggerStamps, key)
}
