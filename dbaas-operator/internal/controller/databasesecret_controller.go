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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasesecrets,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasesecrets/status,verbs=get;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;create;update;patch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
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
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

// DatabaseSecretReconciler reconciles DatabaseSecret objects.
//
// On every reconcile it:
//  1. Runs a pre-flight uniqueness check on the target Secret name.
//  2. Calls POST /api/v3/dbaas/{ns}/databases/get-by-classifier/{type} on the aggregator.
//  3. Re-checks the target Secret for ownership conflicts (race guard).
//  4. Creates or updates the core v1.Secret with the returned connectionProperties.
type DatabaseSecretReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver
}

func (r *DatabaseSecretReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	s := &dbaasv1.DatabaseSecret{}
	if err := r.Get(ctx, req.NamespacedName, s); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	owned, result, err := checkOwnership(ctx, r.Ownership, s.Namespace, s.Name, "DatabaseSecret")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		return result, nil
	}

	original := s.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), s, original, &retErr,
			func(obj *dbaasv1.DatabaseSecret, retErr error) bool {
				return retErr == nil
			},
			"DatabaseSecret")
	}()

	s.Status.Phase = dbaasv1.PhaseProcessing

	// ── Step 6a: Pre-flight validation - require app.kubernetes.io/name label ─────────────────────────
	if s.Labels["app.kubernetes.io/name"] == "" {
		return invalidSpec(ctx, &s.Status.Phase, &s.Status.Conditions, s.Generation,
			r.Recorder, s,
			"label app.kubernetes.io/name is required — its value is used as originService in the get-by-classifier request")
	}

	// ── Step 6b: Pre-flight validation - check whether the target Secret already exists ──────────────
	existingSecret := &corev1.Secret{}
	secretKey := types.NamespacedName{Namespace: s.Namespace, Name: s.Spec.SecretName}
	secretExists := true
	if err := r.Get(ctx, secretKey, existingSecret); err != nil {
		if client.IgnoreNotFound(err) != nil {
			return ctrl.Result{}, err
		}
		secretExists = false
	}

	if secretExists {
		if conflict, msg := r.ownerConflict(s, existingSecret); conflict {
			return r.markSecretConflict(ctx, s, msg)
		}
	}

	// ── Step 6c: Pre-flight validation - check for sibling DatabaseSecret CRs claiming the same name ─
	var siblings dbaasv1.DatabaseSecretList
	if err := r.List(ctx, &siblings, client.InNamespace(s.Namespace)); err != nil {
		return ctrl.Result{}, err
	}
	for i := range siblings.Items {
		if siblings.Items[i].UID != s.UID && siblings.Items[i].Spec.SecretName == s.Spec.SecretName {
			return r.markSecretConflict(ctx, s,
				fmt.Sprintf("another DatabaseSecret %q in namespace %q already claims secretName %q",
					siblings.Items[i].Name, s.Namespace, s.Spec.SecretName))
		}
	}

	// ── Step 7: call aggregator ───────────────────────────────────────────────
	aggReq := &aggregatorclient.GetByClassifierRequest{
		Classifier:    classifierFlatMap(s.Spec.Classifier),
		OriginService: s.Labels["app.kubernetes.io/name"],
		UserRole:      s.Spec.UserRole,
	}
	dbResp, err := r.Aggregator.GetDatabaseByClassifier(ctx, s.Namespace, s.Spec.Type, aggReq)
	if err != nil {
		return r.handleAggregatorErr(ctx, s, err, requestID)
	}

	// ── Step 8: validate connectionProperties ─────────────────────────────────
	if len(dbResp.ConnectionProperties) == 0 {
		return invalidSpec(ctx, &s.Status.Phase, &s.Status.Conditions, s.Generation,
			r.Recorder, s,
			fmt.Sprintf("aggregator returned empty connectionProperties for type=%s", s.Spec.Type))
	}

	// ── Step 9: re-check Secret (race guard) ──────────────────────────────────
	existingSecret = &corev1.Secret{}
	secretExists = true
	if err := r.Get(ctx, secretKey, existingSecret); err != nil {
		if client.IgnoreNotFound(err) != nil {
			return ctrl.Result{}, err
		}
		secretExists = false
	}

	if secretExists {
		if conflict, msg := r.ownerConflict(s, existingSecret); conflict {
			return r.markSecretConflict(ctx, s, msg)
		}
	}

	// ── Step 9 / write Secret ─────────────────────────────────────────────────
	coreSecret := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      s.Spec.SecretName,
			Namespace: s.Namespace,
		},
	}
	secretData, err := connectionPropertiesToSecretData(dbResp.ConnectionProperties)
	if err != nil {
		return ctrl.Result{}, fmt.Errorf("marshal connectionProperties: %w", err)
	}
	_, err = controllerutil.CreateOrUpdate(ctx, r.Client, coreSecret, func() error {
		coreSecret.Data = secretData
		return ctrl.SetControllerReference(s, coreSecret, r.Scheme)
	})
	if err != nil {
		return ctrl.Result{}, fmt.Errorf("create/update Secret %s: %w", s.Spec.SecretName, err)
	}

	// ── Step 10: mark succeeded ───────────────────────────────────────────────
	log.InfoC(ctx, "DatabaseSecret reconciled successfully name=%s secretName=%s", s.Name, s.Spec.SecretName)
	markSucceeded(&s.Status.Phase, &s.Status.Conditions, s.Generation, EventReasonSecretCreated)
	r.Recorder.Eventf(s, corev1.EventTypeNormal, EventReasonSecretCreated,
		"Secret %q created/updated with connection properties (requestId=%s)",
		s.Spec.SecretName, requestID)
	return ctrl.Result{}, nil
}

// ownerConflict returns true when the existing Secret is owned by a resource other than s,
// or has no owner at all. msg describes the conflict.
func (r *DatabaseSecretReconciler) ownerConflict(s *dbaasv1.DatabaseSecret, existing *corev1.Secret) (bool, string) {
	refs := existing.GetOwnerReferences()
	if len(refs) == 0 {
		return true, fmt.Sprintf("Secret %q exists with no ownerReference", existing.Name)
	}
	for _, ref := range refs {
		if ref.Controller != nil && *ref.Controller && ref.UID == s.UID {
			return false, ""
		}
	}
	return true, fmt.Sprintf("Secret %q is already owned by another resource", existing.Name)
}

// markSecretConflict sets InvalidConfiguration/SecretConflict and stops reconciliation.
func (r *DatabaseSecretReconciler) markSecretConflict(ctx context.Context, s *dbaasv1.DatabaseSecret, msg string) (ctrl.Result, error) {
	log.InfoC(ctx, "SecretConflict name=%s reason=%s", s.Name, msg)
	markPermanentFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation, EventReasonSecretConflict, msg)
	r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonSecretConflict, "%s", msg)
	return ctrl.Result{}, nil
}

// handleAggregatorErr maps aggregator errors to the correct phase/conditions/events.
// 401 and 403 are both treated as Unauthorized (transient).
// 404 gets its own DatabaseNotReady reason.
// Everything else is delegated to the shared handleAggregatorError helper.
func (r *DatabaseSecretReconciler) handleAggregatorErr(
	ctx context.Context,
	s *dbaasv1.DatabaseSecret,
	err error,
	requestID string,
) (ctrl.Result, error) {
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) {
		switch aggErr.StatusCode {
		case http.StatusUnauthorized, http.StatusForbidden:
			markTransientFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation,
				EventReasonUnauthorized, aggErr.UserMessage())
			r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonUnauthorized,
				"aggregator rejected credentials (HTTP %d): %s (requestId=%s)",
				aggErr.StatusCode, aggErr.UserMessage(), requestID)
			return ctrl.Result{}, err

		case http.StatusNotFound:
			markTransientFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation,
				EventReasonDatabaseNotReady, aggErr.UserMessage())
			r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonDatabaseNotReady,
				"database not found in dbaas-aggregator, waiting for provisioning (requestId=%s)", requestID)
			return ctrl.Result{}, err
		}
	}
	return handleAggregatorError(&s.Status.Phase, &s.Status.Conditions, s.Generation,
		r.Recorder, s, err, requestID)
}

// connectionPropertiesToSecretData serializes the connectionProperties map as a single
// JSON value stored under the key "connectionProperties" in the Kubernetes Secret.
// The connectionProperties format is arbitrary (any JSON-serializable map) because
// on the dbaas-aggregator side the type is List<Map<String,Object>>.
func connectionPropertiesToSecretData(props map[string]any) (map[string][]byte, error) {
	raw, err := json.Marshal(props)
	if err != nil {
		return nil, err
	}
	return map[string][]byte{
		"connectionProperties": raw,
	}, nil
}

// SetupWithManager sets up the controller with the Manager.
func (r *DatabaseSecretReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DatabaseSecret{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		WithOptions(opts).
		Named("databasesecret").
		Complete(r)
}

func (r *DatabaseSecretReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	list := &dbaasv1.DatabaseSecretList{}
	if err := r.List(ctx, list, client.InNamespace(obj.GetNamespace())); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list DatabaseSecrets in %s: %v", obj.GetNamespace(), err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}
