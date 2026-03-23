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

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/predicate"

	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
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
}

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpolicies,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpolicies/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpolicies/finalizers,verbs=update
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *DbPolicyReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	log := logf.FromContext(ctx)

	dp := &dbaasv1alpha1.DbPolicy{}
	if err := r.Get(ctx, req.NamespacedName, dp); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// Snapshot for the status patch at the end of reconcile.
	original := dp.DeepCopy()

	// Always patch status on exit, even if reconcile fails.
	defer func() {
		patchStatusOnExit(ctx, r.Status(), dp, original, &retErr,
			func(_ *dbaasv1alpha1.DbPolicy, retErr error) bool { return retErr == nil },
			"DbPolicy")
	}()

	// Mark as Processing while we work.
	dp.Status.Phase = dbaasv1alpha1.PhaseProcessing

	// ── Pre-flight validation ─────────────────────────────────────────────────
	// Field-level constraints (microserviceName, services[].name/roles, policy[].type/defaultRole)
	// are enforced by CRD admission. Only the cross-field constraint below cannot be expressed in schema.

	if len(dp.Spec.Services) == 0 && len(dp.Spec.Policy) == 0 {
		return r.invalidSpec(ctx, dp, "spec: at least one of 'services' or 'policy' must be set")
	}

	// ── Call aggregator ───────────────────────────────────────────────────────

	payload := r.buildPayload(dp)
	if _, err := r.Aggregator.ApplyConfig(ctx, payload); err != nil {
		log.Error(err, "failed to apply DbPolicy to dbaas-aggregator")

		var aggErr *aggregatorclient.AggregatorError
		if errors.As(err, &aggErr) {
			switch {
			case aggErr.IsAuthError():
				// 401 — operator credentials or role binding misconfigured; retry.
				markTransientFailure(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation,
					EventReasonUnauthorized, aggErr.UserMessage())
				r.Recorder.Eventf(dp, corev1.EventTypeWarning, EventReasonUnauthorized,
					"dbaas-aggregator rejected operator credentials (HTTP 401): %s", aggErr.UserMessage())
				return ctrl.Result{}, err // requeue with backoff

			case aggErr.IsSpecRejection():
				// 400/403/409/410/422 — aggregator explicitly rejected the spec.
				// Retrying the same payload will not help; wait for a spec change.
				markPermanentFailure(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation,
					EventReasonAggregatorRejected, aggErr.UserMessage())
				r.Recorder.Eventf(dp, corev1.EventTypeWarning, EventReasonAggregatorRejected,
					"dbaas-aggregator rejected request: %s", aggErr.UserMessage())
				return ctrl.Result{}, nil // do not requeue
			}
		}

		// 5xx / network error — transient; requeue with backoff.
		errMsg := err.Error()
		if aggErr != nil {
			errMsg = aggErr.UserMessage()
		}
		markTransientFailure(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation,
			EventReasonAggregatorError, errMsg)
		r.Recorder.Eventf(dp, corev1.EventTypeWarning, EventReasonAggregatorError,
			"dbaas-aggregator error: %s", errMsg)
		return ctrl.Result{}, err
	}

	log.Info("DbPolicy applied successfully", "microserviceName", dp.Spec.MicroserviceName)
	markSucceeded(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation, EventReasonPolicyApplied)
	r.Recorder.Eventf(dp, corev1.EventTypeNormal, EventReasonPolicyApplied,
		"policy applied to dbaas-aggregator (microserviceName=%s)", dp.Spec.MicroserviceName)
	return ctrl.Result{}, nil
}

// invalidSpec is a helper that sets InvalidConfiguration phase + conditions,
// emits a Warning event, and returns (no requeue, nil error) so
// observedGeneration is stamped (permanent error, wait for spec change).
func (r *DbPolicyReconciler) invalidSpec(ctx context.Context, dp *dbaasv1alpha1.DbPolicy, msg string) (ctrl.Result, error) {
	logf.FromContext(ctx).Info("invalid spec", "reason", msg)
	markPermanentFailure(&dp.Status.Phase, &dp.Status.Conditions, dp.Generation,
		EventReasonInvalidSpec, msg)
	r.Recorder.Eventf(dp, corev1.EventTypeWarning, EventReasonInvalidSpec, msg)
	return ctrl.Result{}, nil
}

// buildPayload assembles the DeclarativePayload for POST /api/declarations/v1/apply.
// MicroserviceName goes into metadata (not into the spec that is forwarded to the aggregator).
func (r *DbPolicyReconciler) buildPayload(dp *dbaasv1alpha1.DbPolicy) *aggregatorclient.DeclarativePayload {
	return &aggregatorclient.DeclarativePayload{
		APIVersion: "core.netcracker.com/v1",
		Kind:       "DBaaS",
		SubKind:    "DbPolicy",
		Metadata: aggregatorclient.DeclarativeMeta{
			Name:             dp.Name,
			Namespace:        dp.Namespace,
			MicroserviceName: dp.Spec.MicroserviceName,
		},
		Spec: struct {
			Services                 []dbaasv1alpha1.ServiceRole `json:"services,omitempty"`
			Policy                   []dbaasv1alpha1.PolicyRole  `json:"policy,omitempty"`
			DisableGlobalPermissions bool                        `json:"disableGlobalPermissions,omitempty"`
		}{
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
		For(&dbaasv1alpha1.DbPolicy{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		WithOptions(opts).
		Named("dbpolicy").
		Complete(r)
}
