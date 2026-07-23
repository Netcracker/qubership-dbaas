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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebindings,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebindings/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebindings/finalizers,verbs=update

import (
	"context"
	"strings"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/equality"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
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
//  3. Watch workload resources (ExternalDatabase, DatabaseAccessPolicy, InternalDatabase,
//     and balancing rules)
//     so that any create/delete of a workload in a bound namespace triggers a
//     re-evaluation of the finalizer.
type NamespaceBindingReconciler struct {
	client.Client
	Scheme      *runtime.Scheme
	Recorder    record.EventRecorder
	MyNamespace string
	Ownership   *ownership.OwnershipResolver
	Checker     ownership.BlockingResourceChecker
}

func (r *NamespaceBindingReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

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
	// the finalizer, status, or events — that is the owning instance's
	// responsibility. This return sits above the status defer on purpose: two
	// instances writing conditions to the same object would fight forever. The
	// flip side: a binding whose operatorNamespace matches no running operator
	// keeps an empty status — document that as "unclaimed", it cannot be
	// reported by anyone.
	if nb.Spec.OperatorNamespace != r.MyNamespace {
		log.InfoC(ctx, "NamespaceBinding %s/%s belongs to operatorNamespace=%s (mine=%s) — skipping mutations",
			nb.Namespace, nb.Name, nb.Spec.OperatorNamespace, r.MyNamespace)
		return ctrl.Result{}, nil
	}

	// ── Snapshot + deferred status patch (owning instance only) ──────────────
	// observedGeneration is stamped only when a terminal condition holds for
	// the current generation and the reconcile exits cleanly; a deletion in
	// progress (Ready=False/BindingBlocked) therefore never stamps.
	original := nb.DeepCopy()
	defer func() {
		// Workload create/delete events re-enqueue the binding, so most owner
		// reconciles change nothing: setCondition preserves LastTransitionTime
		// and the phase stays put. Skip the patch entirely then — a per-event
		// status write (LastRequestID alone would differ every time) turns the
		// binding into an API-write amplifier during workload churn.
		if equality.Semantic.DeepEqual(nb.Status, original.Status) {
			return
		}
		// Assigned inside the defer: the finalizer patch below refreshes nb
		// from the API response, which would wipe an in-memory status field
		// assigned before it ran. Conditions are safe — every mark* call
		// happens after the last main-resource patch on its path.
		nb.Status.LastRequestID = requestID
		patchStatusOnExit(ctx, r.Status(), nb, original, &retErr,
			func(obj *dbaasv1.NamespaceBinding, retErr error) bool {
				return retErr == nil && isTerminal(obj.Status.Conditions, obj.Generation)
			},
			"NamespaceBinding")
	}()

	// ── Deletion path ────────────────────────────────────────────────────────
	if !nb.DeletionTimestamp.IsZero() {
		if !controllerutil.ContainsFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer) {
			// Finalizer already removed; nothing left to do.
			return ctrl.Result{}, nil
		}

		kinds, err := r.Checker.BlockingKinds(ctx, nb.Namespace)
		if err != nil {
			log.ErrorC(ctx, "checking blocking resources for NamespaceBinding %s/%s: %v", nb.Namespace, nb.Name, err)
			markTransientFailure(&nb.Status.Phase, &nb.Status.Conditions, nb.Generation,
				ReasonOwnershipCheckError, err.Error())
			return ctrl.Result{}, err
		}

		if len(kinds) > 0 {
			msg := "deletion deferred: " + strings.Join(kinds, ", ") + " resources still present in the namespace"
			log.InfoC(ctx, "NamespaceBinding %s/%s blocked by dbaas resources kinds=%v — deletion deferred", nb.Namespace, nb.Name, kinds)
			nb.Status.Phase = dbaasv1.PhaseProcessing
			setCondition(&nb.Status.Conditions, nb.Generation,
				conditionTypeReady, metav1.ConditionFalse, EventReasonBindingBlocked, msg)
			setCondition(&nb.Status.Conditions, nb.Generation,
				conditionTypeStalled, metav1.ConditionFalse, EventReasonBindingBlocked,
				"Deletion proceeds automatically once the blocking resources are deleted.")
			r.Recorder.Eventf(nb, corev1.EventTypeWarning, EventReasonBindingBlocked,
				"%s (requestId=%s)", msg, requestID)
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

	// Idempotent on every owner reconcile, not only when the finalizer is first
	// added: workload create/delete events re-enqueue the binding, and
	// setCondition preserves LastTransitionTime when nothing changed.
	markSucceeded(&nb.Status.Phase, &nb.Status.Conditions, nb.Generation, EventReasonBindingRegistered)
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

// workloadLifecyclePredicate limits the workload watches to create and delete
// events. The binding only cares whether blocking resources exist, and a
// spec or status update cannot change that — without the filter every status
// write on every workload CR re-enqueued the binding.
var workloadLifecyclePredicate = predicate.Funcs{
	CreateFunc:  func(event.CreateEvent) bool { return true },
	DeleteFunc:  func(event.DeleteEvent) bool { return true },
	UpdateFunc:  func(event.UpdateEvent) bool { return false },
	GenericFunc: func(event.GenericEvent) bool { return false },
}

// SetupWithManager registers the controller and configures watches.
func (r *NamespaceBindingReconciler) SetupWithManager(
	mgr ctrl.Manager,
	opts ctrlcontroller.Options,
) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.NamespaceBinding{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Watches(
			&dbaasv1.ExternalDatabase{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			builder.WithPredicates(workloadLifecyclePredicate),
		).
		Watches(
			&dbaasv1.DatabaseAccessPolicy{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			builder.WithPredicates(workloadLifecyclePredicate),
		).
		Watches(
			&dbaasv1.InternalDatabase{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			builder.WithPredicates(workloadLifecyclePredicate),
		).
		Watches(
			&dbaasv1.MicroserviceBalancingRule{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			builder.WithPredicates(workloadLifecyclePredicate),
		).
		Watches(
			&dbaasv1.NamespaceBalancingRule{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			builder.WithPredicates(workloadLifecyclePredicate),
		).
		// PermanentBalancingRule is intentionally NOT watched here: it is
		// operator-namespace-only and decoupled from NamespaceBinding, so it never
		// blocks a (tenant) NamespaceBinding's deletion and needs no re-enqueue.
		Watches(
			&dbaasv1.DatabaseSecretClaim{},
			handler.EnqueueRequestsFromMapFunc(enqueueBindingForWorkload),
			builder.WithPredicates(workloadLifecyclePredicate),
		).
		WithOptions(opts).
		Named("namespacebinding").
		Complete(r)
}

// SetupWithManagerOpts is an alias that passes zero controller options.
// Useful in tests where custom rate limiters are not needed.
func (r *NamespaceBindingReconciler) SetupWithManagerOpts(mgr ctrl.Manager) error {
	return r.SetupWithManager(mgr, ctrlcontroller.Options{})
}

// Ensure runtime.Object is satisfied (required for recorder.Eventf).
var _ runtime.Object = &dbaasv1.NamespaceBinding{}
