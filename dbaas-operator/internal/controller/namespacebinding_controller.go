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

// NamespaceBindingReconciler reconciles NamespaceBinding objects.
//
// Responsibilities:
//  1. Keep the ownership cache (OwnershipResolver) up-to-date on every
//     create/update/delete.
//  2. Manage the NamespaceBindingProtectionFinalizer: add it when the namespace
//     contains blocking dbaas resources; remove it (allowing deletion) only once
//     those resources are gone.
//  3. Watch workload resources (ExternalDatabase, and optionally
//     DatabaseDeclaration + DbPolicy when alpha APIs are enabled) so that any
//     create/delete of a workload in a bound namespace triggers a re-evaluation
//     of the finalizer.
type NamespaceBindingReconciler struct {
	client.Client
	Scheme      *runtime.Scheme
	Recorder    record.EventRecorder
	MyNamespace string
	Ownership   *ownership.OwnershipResolver
	Checker     ownership.BlockingResourceChecker
}

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebindings,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebindings/finalizers,verbs=update

func (r *NamespaceBindingReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	requestID := uuid.New().String()
	ctx = ctxmanager.InitContext(ctx, map[string]any{
		xRequestID: requestID,
	})

	nb := &dbaasv1.NamespaceBinding{}
	if err := r.Get(ctx, req.NamespacedName, nb); err != nil {
		if client.IgnoreNotFound(err) == nil {
			// Binding was deleted — evict from cache.
			r.Ownership.Forget(req.Namespace)
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}

	// ── Keep ownership cache current ─────────────────────────────────────────
	r.Ownership.SetOwner(nb.Namespace, nb.Spec.OperatorNamespace)

	// ── Foreign binding — cache update only, no mutations ────────────────────
	// Only the operator instance whose CLOUD_NAMESPACE matches spec.operatorNamespace
	// owns this binding.  A foreign operator must update its cache (so workload
	// reconcilers know the namespace is Foreign / not theirs) but must not touch
	// the finalizer or emit events — that is the owning instance's responsibility.
	if nb.Spec.OperatorNamespace != r.MyNamespace {
		log.InfoC(ctx, "NamespaceBinding %s/%s belongs to operatorNamespace=%s (mine=%s) — skipping mutations",
			nb.Namespace, nb.Name, nb.Spec.OperatorNamespace, r.MyNamespace)
		return ctrl.Result{}, nil
	}

	// ── Deletion path ────────────────────────────────────────────────────────
	if !nb.DeletionTimestamp.IsZero() {
		if !controllerutil.ContainsFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer) {
			// Finalizer already removed; nothing left to do.
			return ctrl.Result{}, nil
		}

		blocking, err := r.Checker.HasBlockingResources(ctx, nb.Namespace)
		if err != nil {
			log.ErrorC(ctx, "checking blocking resources for NamespaceBinding %s/%s: %v", nb.Namespace, nb.Name, err)
			return ctrl.Result{}, err
		}

		if blocking {
			log.InfoC(ctx, "NamespaceBinding %s/%s blocked by dbaas resources — deletion deferred", nb.Namespace, nb.Name)
			r.Recorder.Eventf(nb, corev1.EventTypeWarning, EventReasonBindingBlocked,
				"deletion deferred: namespace still contains dbaas workload resources (requestId=%s)", requestID)
			return ctrl.Result{}, nil
		}

		// No blocking resources — remove finalizer to allow deletion.
		patch := client.MergeFrom(nb.DeepCopy())
		controllerutil.RemoveFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer)
		if err := r.Patch(ctx, nb, patch); err != nil {
			return ctrl.Result{}, err
		}
		r.Ownership.Forget(nb.Namespace)
		log.InfoC(ctx, "NamespaceBinding %s/%s finalizer removed, deletion unblocked", nb.Namespace, nb.Name)
		return ctrl.Result{}, nil
	}

	// ── Creation / update path ───────────────────────────────────────────────
	if !controllerutil.ContainsFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer) {
		patch := client.MergeFrom(nb.DeepCopy())
		controllerutil.AddFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer)
		if err := r.Patch(ctx, nb, patch); err != nil {
			return ctrl.Result{}, err
		}
		log.InfoC(ctx, "NamespaceBinding %s/%s registered operatorNamespace=%s", nb.Namespace, nb.Name, nb.Spec.OperatorNamespace)
		r.Recorder.Eventf(nb, corev1.EventTypeNormal, EventReasonBindingRegistered,
			"namespace %s bound to operatorNamespace %s (requestId=%s)", nb.Namespace, nb.Spec.OperatorNamespace, requestID)
	}

	return ctrl.Result{}, nil
}

// enqueueBindingForWorkload maps any workload object to the NamespaceBinding
// named "binding" in the same namespace.  This ensures that when a workload
// is created or deleted the controller re-evaluates the finalizer.
func enqueueBindingForWorkload(_ context.Context, obj client.Object) []reconcile.Request {
	return []reconcile.Request{
		{NamespacedName: client.ObjectKey{
			Namespace: obj.GetNamespace(),
			Name:      dbaasv1.NamespaceBindingName,
		}},
	}
}

// SetupWithManager registers the controller and configures watches.
// alphaEnabled controls whether DatabaseDeclaration and DbPolicy watches are added.
func (r *NamespaceBindingReconciler) SetupWithManager(
	mgr ctrl.Manager,
	opts ctrlcontroller.Options,
	alphaEnabled bool,
) error {
	b := ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.NamespaceBinding{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Watches(
			&dbaasv1.ExternalDatabase{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
		).
		WithOptions(opts).
		Named("namespacebinding")

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
func (r *NamespaceBindingReconciler) SetupWithManagerOpts(mgr ctrl.Manager, alphaEnabled bool) error {
	return r.SetupWithManager(mgr, ctrlcontroller.Options{}, alphaEnabled)
}

// Ensure runtime.Object is satisfied (required for recorder.Eventf).
var _ runtime.Object = &dbaasv1.NamespaceBinding{}
