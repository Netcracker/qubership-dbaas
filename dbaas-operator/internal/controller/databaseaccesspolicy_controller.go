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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databaseaccesspolicies,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databaseaccesspolicies/status,verbs=get;update;patch

import (
	"context"
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

// DatabaseAccessPolicyReconciler reconciles DatabaseAccessPolicy objects.
//
// On every reconcile it validates the spec, assembles a DeclarativePayload, calls
// POST /api/declarations/v1/apply on dbaas-aggregator, and updates the CR status.
// Key outcomes are also emitted as Kubernetes Events.
type DatabaseAccessPolicyReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver

	bindingTriggerTracker
}

func (r *DatabaseAccessPolicyReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	dp := &dbaasv1.DatabaseAccessPolicy{}
	if err := r.Get(ctx, req.NamespacedName, dp); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(req.Namespace + "/" + req.Name)
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := req.Namespace + "/" + req.Name
	bindingTriggered := r.consumeBindingTrigger(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, dp.Namespace, dp.Name, "DatabaseAccessPolicy")
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
	recordReconcileTrigger(controllerDAP, trigger)

	original := dp.DeepCopy()

	defer func() {
		patchStatusOnExit(ctx, r.Status(), dp, original, &retErr,
			func(_ *dbaasv1.DatabaseAccessPolicy, retErr error) bool { return retErr == nil },
			"DatabaseAccessPolicy")
	}()

	dp.Status.Phase = dbaasv1.PhaseProcessing

	// Field-level constraints (microserviceName, services[].name/roles, policy[].type/defaultRole)
	// are enforced by CRD admission. Only the cross-field constraint below cannot be expressed in schema.

	if len(dp.Spec.Services) == 0 && len(dp.Spec.Policy) == 0 {
		return invalidSpec(ctx, &dp.Status.Phase, &dp.Status.Conditions, dp.Generation, r.Recorder, dp, "spec: at least one of 'services' or 'policy' must be set")
	}

	payload := r.buildPayload(dp)
	dp.Status.LastRequestID = requestID
	aggStart := time.Now()
	_, aggErr := r.Aggregator.ApplyConfig(ctx, payload)
	recordAggregatorCall(controllerDAP, operationApplyConfig, aggStart, aggErr)
	if aggErr != nil {
		log.ErrorC(ctx, "failed to apply DatabaseAccessPolicy to dbaas-aggregator: %v", aggErr)
		return handleAggregatorError(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation, r.Recorder, dp, aggErr, requestID)
	}

	log.InfoC(ctx, "DatabaseAccessPolicy applied successfully microserviceName=%v", dp.Spec.MicroserviceName)
	markSucceeded(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation, EventReasonPolicyApplied)
	r.Recorder.Eventf(dp, corev1.EventTypeNormal, EventReasonPolicyApplied,
		"policy applied to dbaas-aggregator (microserviceName=%s, requestId=%s)",
		dp.Spec.MicroserviceName, requestID)
	return ctrl.Result{}, nil
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
func (r *DatabaseAccessPolicyReconciler) buildPayload(dp *dbaasv1.DatabaseAccessPolicy) *aggregatorclient.DeclarativePayload {
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

// SetupWithManager registers watches for spec changes and NamespaceBinding fan-out.
func (r *DatabaseAccessPolicyReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DatabaseAccessPolicy{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		// Re-enqueue all DbPolicies in a namespace when its NamespaceBinding
		// is created or updated, so existing CRs are reconciled without waiting for
		// a spec change.
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		WithOptions(opts).
		Named("databaseaccesspolicy").
		Complete(r)
}

// enqueueForBinding maps an NamespaceBinding event to reconcile requests for
// all DbPolicies that live in the same namespace.
func (r *DatabaseAccessPolicyReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	return enqueueForBindingList(ctx, r.Client, &dbaasv1.DatabaseAccessPolicyList{}, obj.GetNamespace(),
		func(o client.Object) { r.stampBindingTrigger(o.GetNamespace() + "/" + o.GetName()) })
}
