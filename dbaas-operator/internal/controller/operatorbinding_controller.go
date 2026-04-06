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

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

// OperatorBindingReconciler reconciles OperatorBinding objects.
//
// Responsibilities:
//  1. Keep the ownership cache (OwnershipResolver) up-to-date on every
//     create/update/delete.
//  2. Manage the OperatorBindingProtectionFinalizer: add it when the namespace
//     contains blocking dbaas resources; remove it (allowing deletion) only once
//     those resources are gone.
//  3. Watch workload resources (ExternalDatabase, and optionally
//     DatabaseDeclaration + DbPolicy when alpha APIs are enabled) so that any
//     create/delete of a workload in a bound namespace triggers a re-evaluation
//     of the finalizer.
type OperatorBindingReconciler struct {
	client.Client
	Scheme      *runtime.Scheme
	Recorder    record.EventRecorder
	MyNamespace string
	Ownership   *ownership.OwnershipResolver
	Checker     ownership.BlockingResourceChecker
}

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=operatorbindings,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=operatorbindings/finalizers,verbs=update

func (r *OperatorBindingReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	requestID := uuid.New().String()
	ctx = ctxmanager.InitContext(ctx, map[string]interface{}{
		xRequestID: requestID,
	})

	ob := &dbaasv1.OperatorBinding{}
	if err := r.Get(ctx, req.NamespacedName, ob); err != nil {
		if client.IgnoreNotFound(err) == nil {
			// Binding was deleted — evict from cache.
			r.Ownership.Forget(req.Namespace)
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	// ── Keep ownership cache current ─────────────────────────────────────────
	r.Ownership.SetOwner(ob.Namespace, ob.Spec.Location)

	// ── Deletion path ────────────────────────────────────────────────────────
	if !ob.DeletionTimestamp.IsZero() {
		if !controllerutil.ContainsFinalizer(ob, dbaasv1.OperatorBindingProtectionFinalizer) {
			// Finalizer already removed; nothing left to do.
			return ctrl.Result{}, nil
		}

		blocking, err := r.Checker.HasBlockingResources(ctx, ob.Namespace)
		if err != nil {
			log.ErrorC(ctx, "checking blocking resources for OperatorBinding %s/%s: %v", ob.Namespace, ob.Name, err)
			return ctrl.Result{}, err
		}

		if blocking {
			log.InfoC(ctx, "OperatorBinding %s/%s blocked by dbaas resources — deletion deferred", ob.Namespace, ob.Name)
			r.Recorder.Eventf(ob, corev1.EventTypeWarning, EventReasonBindingBlocked,
				"deletion deferred: namespace still contains dbaas workload resources (requestId=%s)", requestID)
			return ctrl.Result{}, nil
		}

		// No blocking resources — remove finalizer to allow deletion.
		patch := client.MergeFrom(ob.DeepCopy())
		controllerutil.RemoveFinalizer(ob, dbaasv1.OperatorBindingProtectionFinalizer)
		if err := r.Patch(ctx, ob, patch); err != nil {
			return ctrl.Result{}, err
		}
		r.Ownership.Forget(ob.Namespace)
		log.InfoC(ctx, "OperatorBinding %s/%s finalizer removed, deletion unblocked", ob.Namespace, ob.Name)
		return ctrl.Result{}, nil
	}

	// ── Creation / update path ───────────────────────────────────────────────
	if !controllerutil.ContainsFinalizer(ob, dbaasv1.OperatorBindingProtectionFinalizer) {
		patch := client.MergeFrom(ob.DeepCopy())
		controllerutil.AddFinalizer(ob, dbaasv1.OperatorBindingProtectionFinalizer)
		if err := r.Patch(ctx, ob, patch); err != nil {
			return ctrl.Result{}, err
		}
		log.InfoC(ctx, "OperatorBinding %s/%s registered location=%s", ob.Namespace, ob.Name, ob.Spec.Location)
		r.Recorder.Eventf(ob, corev1.EventTypeNormal, EventReasonBindingRegistered,
			"namespace %s bound to location %s (requestId=%s)", ob.Namespace, ob.Spec.Location, requestID)
	}

	return ctrl.Result{}, nil
}

// enqueueBindingForWorkload maps any workload object to the OperatorBinding
// named "registration" in the same namespace.  This ensures that when a workload
// is created or deleted the controller re-evaluates the finalizer.
func enqueueBindingForWorkload(_ context.Context, obj client.Object) []reconcile.Request {
	return []reconcile.Request{
		{NamespacedName: client.ObjectKey{
			Namespace: obj.GetNamespace(),
			Name:      dbaasv1.OperatorBindingName,
		}},
	}
}

// SetupWithManager registers the controller and configures watches.
// alphaEnabled controls whether DatabaseDeclaration and DbPolicy watches are added.
func (r *OperatorBindingReconciler) SetupWithManager(
	mgr ctrl.Manager,
	opts ctrlcontroller.Options,
	alphaEnabled bool,
) error {
	b := ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.OperatorBinding{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Watches(
			&dbaasv1.ExternalDatabase{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
		).
		WithOptions(opts).
		Named("operatorbinding")

	if alphaEnabled {
		b = b.
			Watches(
				&dbaasv1alpha1.DatabaseDeclaration{},
				handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			).
			Watches(
				&dbaasv1alpha1.DbPolicy{},
				handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			)
	}

	return b.Complete(r)
}

// SetupWithManagerOpts is an alias that passes zero controller options.
// Useful in tests where custom rate limiters are not needed.
func (r *OperatorBindingReconciler) SetupWithManagerOpts(mgr ctrl.Manager, alphaEnabled bool) error {
	return r.SetupWithManager(mgr, ctrlcontroller.Options{}, alphaEnabled)
}

// Ensure runtime.Object is satisfied (required for recorder.Eventf).
var _ runtime.Object = &dbaasv1.OperatorBinding{}
