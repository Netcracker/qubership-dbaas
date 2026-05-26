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
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
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

// secretNameIndex is the field index key for DatabaseSecret.Spec.SecretName,
// used to resolve sibling conflicts in O(1) instead of a full namespace scan.
const secretNameIndex = "spec.secretName"

// classifierTypeIndex is the field index key for the canonical
// (spec.classifier, spec.type) pair. Used by the rotation webhook handler to
// resolve incoming RotationOccurred / RestoreCompleted events from
// dbaas-aggregator to the affected DatabaseSecret CRs without scanning the
// whole namespace.
//
// The key intentionally does NOT include spec.userRole. The aggregator resolves
// userRole through DbPolicy (defaultRole, additionalRole) and the global
// permission registry (see DatabaseRolesService.getSupportRole on the
// aggregator side), so an operator-side spec.userRole="" may effectively be
// any policy-defined role, and a spec.userRole="X" may even map to a different
// effective role via additionalRole rules. Replicating that resolution locally
// would create cache-coherence problems whenever the DbPolicy changes.
// Instead, the webhook handler fans out to every DatabaseSecret matching the
// classifier+type, and each reconcile's content-aware compare guards against
// no-op Secret writes — the wasted reconciles for non-matching roles are
// bounded (typically 1-3 CRs per classifier) and harmless.
const classifierTypeIndex = "spec.classifier+type"

// classifierIndexKey canonicalizes (classifier, type) into a deterministic
// string suitable for cache field-index lookup. The canonical form is
// "<type>|<json-of-classifierFlatMap>", where json.Marshal on map[string]any
// guarantees alphabetical key ordering (including nested customKeys). Two
// CRs with the same classifier content produce the same key regardless of
// how their JSON was originally ordered.
func classifierIndexKey(c dbaasv1.Classifier, dbType string) string {
	flat := classifierFlatMap(c)
	raw, err := json.Marshal(flat)
	if err != nil {
		// classifierFlatMap only produces JSON-serializable values
		// (string/number/bool/nested map); marshal failure is not expected,
		// but if it happens we still want a deterministic-on-content fallback.
		log.Warnf("classifierIndexKey: marshal of classifier failed type=%s err=%v", dbType, err)
		return dbType + "|"
	}
	return dbType + "|" + string(raw)
}

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
				return retErr == nil && result.RequeueAfter == 0
			},
			"DatabaseSecret")
	}()

	s.Status.Phase = dbaasv1.PhaseProcessing

	// ── Pre-flight validations (spec + sibling/Secret ownership) ──────────────
	if res, stop, err := r.preflightValidate(ctx, s); stop {
		return res, err
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
	// Success — drop the DatabaseNotFound wait marker so the timeout state
	// (if previously reached) is cleared.
	s.Status.FirstNotFoundAt = nil

	// ── Step 8: validate connectionProperties ─────────────────────────────────
	// An empty connectionProperties map on HTTP 200 is not expected from a healthy
	// aggregator+adapter pair (the aggregator throws on missing role rather than
	// returning an empty payload). Treat it as transient — a momentary inconsistency
	// in the adapter or role registry — and requeue. The user's spec is still valid.
	if len(dbResp.ConnectionProperties) == 0 {
		msg := fmt.Sprintf("aggregator returned empty connectionProperties for type=%s", s.Spec.Type)
		log.InfoC(ctx, "empty connectionProperties name=%s type=%s requestId=%s", s.Name, s.Spec.Type, requestID)
		markTransientFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation,
			EventReasonEmptyConnectionProperties, msg)
		r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonEmptyConnectionProperties,
			"%s (requestId=%s)", msg, requestID)
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
	}

	// ── Step 9 / write Secret ─────────────────────────────────────────────────
	secretData, err := connectionPropertiesToSecretData(dbResp.ConnectionProperties)
	if err != nil {
		return ctrl.Result{}, fmt.Errorf("marshal connectionProperties: %w", err)
	}
	secretKey := types.NamespacedName{Namespace: s.Namespace, Name: s.Spec.SecretName}
	return r.writeSecret(ctx, s, secretData, secretKey, requestID)
}

// preflightValidate runs all spec-level and pre-aggregator validations:
// classifier.namespace consistency, the required app.kubernetes.io/name label,
// ownership of any pre-existing target Secret, and the sibling-CR tiebreak that
// resolves spec.secretName collisions deterministically (older claimant wins).
// It returns stop=true when a terminal state has been set on the CR and the
// caller must return res/err immediately; stop=false means validation passed
// and the reconcile may proceed to the aggregator call.
func (r *DatabaseSecretReconciler) preflightValidate(
	ctx context.Context,
	s *dbaasv1.DatabaseSecret,
) (ctrl.Result, bool, error) {
	if ns := s.Spec.Classifier.Namespace; ns != "" && ns != s.Namespace {
		res, err := invalidSpec(ctx, &s.Status.Phase, &s.Status.Conditions, s.Generation,
			r.Recorder, s,
			fmt.Sprintf("spec.classifier.namespace %q must match metadata.namespace %q",
				ns, s.Namespace))
		return res, true, err
	}

	if s.Labels["app.kubernetes.io/name"] == "" {
		res, err := invalidSpec(ctx, &s.Status.Phase, &s.Status.Conditions, s.Generation,
			r.Recorder, s,
			"label app.kubernetes.io/name is required — its value is used as originService in the get-by-classifier request")
		return res, true, err
	}

	// Check whether the target Secret already exists and is owned by another CR.
	existingSecret := &corev1.Secret{}
	secretKey := types.NamespacedName{Namespace: s.Namespace, Name: s.Spec.SecretName}
	if err := r.Get(ctx, secretKey, existingSecret); err == nil {
		if conflict, msg := r.ownerConflict(s, existingSecret); conflict {
			res, err := r.markSecretConflict(ctx, s, msg)
			return res, true, err
		}
	} else if client.IgnoreNotFound(err) != nil {
		return ctrl.Result{}, true, err
	}

	// Sibling-CR tiebreak: the older claimant (by creationTimestamp, UID on tie)
	// wins. Only the younger CR(s) move to SecretConflict. Avoids the symmetric
	// deadlock where both CRs would fail and neither could recover when one is
	// deleted.
	var siblings dbaasv1.DatabaseSecretList
	if err := r.List(ctx, &siblings,
		client.InNamespace(s.Namespace),
		client.MatchingFields{secretNameIndex: s.Spec.SecretName},
	); err != nil {
		return ctrl.Result{}, true, err
	}
	for i := range siblings.Items {
		sib := &siblings.Items[i]
		if sib.UID == s.UID {
			continue
		}
		if isOlderClaimant(sib, s) {
			res, err := r.markSecretConflict(ctx, s,
				fmt.Sprintf("another DatabaseSecret %q in namespace %q already claims secretName %q (older claimant wins)",
					sib.Name, s.Namespace, s.Spec.SecretName))
			return res, true, err
		}
	}

	return ctrl.Result{}, false, nil
}

// writeSecret persists secretData into the target Kubernetes Secret following
// the Step 9.1–9.4 race-aware sequence:
//
//	9.1 Try Create. On success → mark CR Succeeded.
//	9.2 On AlreadyExists, re-fetch the Secret. On NotFound (deletion race),
//	    retry Create inline so the next reconcile does not need another
//	    aggregator round-trip.
//	9.3 If the existing Secret is owned by another resource → SecretConflict.
//	9.4 Otherwise Update idempotently. On NotFound (GC race), recreate the
//	    Secret and mark Succeeded if the recreate Create returns nil.
//
// All success paths emit a Normal SecretCreated event and mark the CR succeeded.
func (r *DatabaseSecretReconciler) writeSecret(
	ctx context.Context,
	s *dbaasv1.DatabaseSecret,
	secretData map[string][]byte,
	secretKey types.NamespacedName,
	requestID string,
) (ctrl.Result, error) {
	// Step 9.1 — TRY CREATE.
	newSecret, err := r.buildOwnedSecret(s, secretData)
	if err != nil {
		return ctrl.Result{}, err
	}

	err = r.Create(ctx, newSecret)
	if err == nil {
		r.markSecretSucceeded(s, requestID, "Secret %q created/updated with connection properties (requestId=%s)")
		return ctrl.Result{}, nil
	}
	if !apierrors.IsAlreadyExists(err) {
		return ctrl.Result{}, err
	}

	// Step 9.2 — AlreadyExists → RE-FETCH.
	existing := &corev1.Secret{}
	if err := r.Get(ctx, secretKey, existing); err != nil {
		if apierrors.IsNotFound(err) {
			// Race: Secret deleted between AlreadyExists and this Get.
			// newSecret is still valid; retry Create directly so the next
			// reconcile does not need another aggregator round-trip.
			retryErr := r.Create(ctx, newSecret)
			if retryErr == nil {
				r.markSecretSucceeded(s, requestID, "Secret %q created after deletion race (requestId=%s)")
				return ctrl.Result{}, nil
			}
			if !apierrors.IsAlreadyExists(retryErr) {
				return ctrl.Result{}, retryErr
			}
			// Double race — let next reconcile reconverge.
			return ctrl.Result{RequeueAfter: time.Second}, nil
		}
		return ctrl.Result{}, err
	}

	// Step 9.3 — OWNER CONFLICT CHECK.
	if conflict, msg := r.ownerConflict(s, existing); conflict {
		return r.markSecretConflict(ctx, s, msg)
	}

	// Step 9.4 — UPDATE (idempotent).
	return r.updateOwnedSecret(ctx, s, existing, secretData, requestID)
}

// updateOwnedSecret performs the idempotent Update step (9.4) on a Secret that
// we have just confirmed is either already owned by s or unowned. It recreates
// the Secret if the Update fails with NotFound (GC raced between fetch and
// update). On any successful write it marks the CR succeeded.
func (r *DatabaseSecretReconciler) updateOwnedSecret(
	ctx context.Context,
	s *dbaasv1.DatabaseSecret,
	existing *corev1.Secret,
	secretData map[string][]byte,
	requestID string,
) (ctrl.Result, error) {
	updated := existing.DeepCopy()
	updated.Data = secretData
	if updated.Labels == nil {
		updated.Labels = make(map[string]string)
	}
	updated.Labels["app.kubernetes.io/managed-by"] = "dbaas-operator"
	updated.Labels["app.kubernetes.io/name"] = s.Labels["app.kubernetes.io/name"]

	if !metav1.IsControlledBy(updated, s) {
		if err := ctrl.SetControllerReference(s, updated, r.Scheme); err != nil {
			return ctrl.Result{}, err
		}
	}

	if err := r.Update(ctx, updated); err != nil {
		switch {
		case apierrors.IsConflict(err):
			return ctrl.Result{}, err
		case apierrors.IsNotFound(err):
			// GC deleted the Secret between re-fetch and Update; recreate it.
			recreated, buildErr := r.buildOwnedSecret(s, secretData)
			if buildErr != nil {
				return ctrl.Result{}, buildErr
			}
			createErr := r.Create(ctx, recreated)
			if createErr == nil {
				r.markSecretSucceeded(s, requestID, "Secret %q recreated after deletion (requestId=%s)")
				return ctrl.Result{}, nil
			}
			if !apierrors.IsAlreadyExists(createErr) {
				return ctrl.Result{}, createErr
			}
			// AlreadyExists — another actor beat us with unknown content; let
			// the next reconcile re-fetch and reconverge.
			return ctrl.Result{RequeueAfter: time.Second}, nil
		default:
			return ctrl.Result{}, err
		}
	}
	r.markSecretSucceeded(s, requestID, "Secret %q created/updated with connection properties (requestId=%s)")
	return ctrl.Result{}, nil
}

// markSecretSucceeded marks the CR Succeeded, emits a Normal SecretCreated
// event, and logs a confirmation line. eventFormat must be a printf-style
// format string with two placeholders: the secret name and the request ID.
func (r *DatabaseSecretReconciler) markSecretSucceeded(s *dbaasv1.DatabaseSecret, requestID, eventFormat string) {
	log.Infof("DatabaseSecret reconciled successfully name=%s secretName=%s", s.Name, s.Spec.SecretName)
	markSucceeded(&s.Status.Phase, &s.Status.Conditions, s.Generation, EventReasonSecretCreated)
	r.Recorder.Eventf(s, corev1.EventTypeNormal, EventReasonSecretCreated, eventFormat, s.Spec.SecretName, requestID)
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

// handleAggregatorErr maps aggregator errors to phase/conditions/events for DatabaseSecret.
// It injects the DatabaseNotFound case before delegating to the shared handler:
//   - 404 + CORE-DBAAS-4006  → BackingOff / DatabaseNotFound  (transient, DB not yet registered)
//   - 404 without TMF body   → AggregatorError / BackingOff    (BG edge: no active namespace)
//   - 401                    → BackingOff / Unauthorized        (transient)
//   - 400 / 403              → InvalidConfiguration / AggregatorRejected (permanent)
//   - 5xx / network          → BackingOff / AggregatorError     (transient)
//
// On a continuous DatabaseNotFound streak longer than databaseNotFoundTimeout
// the Ready reason switches to DatabaseNotFoundTimeout and per-cycle Warning
// events stop, but the controller keeps polling so the CR can self-heal if
// the database eventually appears.
func (r *DatabaseSecretReconciler) handleAggregatorErr(
	ctx context.Context,
	s *dbaasv1.DatabaseSecret,
	err error,
	requestID string,
) (ctrl.Result, error) {
	var aggErr *aggregatorclient.AggregatorError
	if errors.As(err, &aggErr) && aggErr.IsDatabaseNotFound() {
		now := metav1.Now()
		if s.Status.FirstNotFoundAt == nil {
			s.Status.FirstNotFoundAt = &now
		}
		elapsed := now.Sub(s.Status.FirstNotFoundAt.Time)

		if elapsed < databaseNotFoundTimeout {
			markTransientFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation,
				EventReasonDatabaseNotFound, aggErr.UserMessage())
			r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonDatabaseNotFound,
				"database not found in dbaas-aggregator, waiting for provisioning (requestId=%s)", requestID)
			return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
		}

		// Past the timeout: emit the one-shot DatabaseNotFoundTimeout Warning
		// only on the reconcile that first crosses the threshold. The Ready
		// reason itself is the marker — once it is DatabaseNotFoundTimeout, no
		// further events are emitted, but polling continues so a late-arriving
		// database can still unstick the CR.
		ready := meta.FindStatusCondition(s.Status.Conditions, conditionTypeReady)
		if ready == nil || ready.Reason != EventReasonDatabaseNotFoundTimeout {
			log.InfoC(ctx, "DatabaseNotFound timeout exceeded name=%s elapsed=%s requestId=%s",
				s.Name, elapsed.Round(time.Second), requestID)
			r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonDatabaseNotFoundTimeout,
				"database not found in dbaas-aggregator for %s; polling continues but operator action may be required (requestId=%s)",
				elapsed.Round(time.Second), requestID)
		}
		markTransientFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation,
			EventReasonDatabaseNotFoundTimeout,
			fmt.Sprintf("database not found in dbaas-aggregator for %s — operator action may be required",
				elapsed.Round(time.Second)))
		return ctrl.Result{RequeueAfter: pollRequeueAfter}, nil
	}
	// Non-NotFound failure path — drop the wait marker so a future 404 starts a fresh streak.
	s.Status.FirstNotFoundAt = nil
	return handleAggregatorError(&s.Status.Phase, &s.Status.Conditions, s.Generation,
		r.Recorder, s, err, requestID)
}

// connectionPropertiesToSecretData serializes the connectionProperties map as a single
// JSON value stored under the key "connectionProperties" in the Kubernetes Secret.
// The aggregator returns a single map (DatabaseResponseV3SingleCP.connectionProperties)
// for the requested userRole; the map shape is dynamic and dictated by the adapter.
func connectionPropertiesToSecretData(props map[string]any) (map[string][]byte, error) {
	raw, err := json.Marshal(props)
	if err != nil {
		return nil, err
	}
	return map[string][]byte{
		"connectionProperties.json": raw,
	}, nil
}

// isOlderClaimant returns true when a was created strictly before b. On equal
// creationTimestamps it falls back to lexical UID comparison so the result is
// stable across both peers (otherwise a tie would leave both CRs as "younger"
// and neither would lose).
func isOlderClaimant(a, b *dbaasv1.DatabaseSecret) bool {
	if a.CreationTimestamp.Before(&b.CreationTimestamp) {
		return true
	}
	if b.CreationTimestamp.Before(&a.CreationTimestamp) {
		return false
	}
	return string(a.UID) < string(b.UID)
}

// SetupWithManager sets up the controller with the Manager.
func (r *DatabaseSecretReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	if err := mgr.GetFieldIndexer().IndexField(
		context.Background(),
		&dbaasv1.DatabaseSecret{},
		secretNameIndex,
		func(obj client.Object) []string {
			return []string{obj.(*dbaasv1.DatabaseSecret).Spec.SecretName}
		},
	); err != nil {
		return err
	}

	if err := mgr.GetFieldIndexer().IndexField(
		context.Background(),
		&dbaasv1.DatabaseSecret{},
		classifierTypeIndex,
		func(obj client.Object) []string {
			ds := obj.(*dbaasv1.DatabaseSecret)
			return []string{classifierIndexKey(ds.Spec.Classifier, ds.Spec.Type)}
		},
	); err != nil {
		return err
	}

	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DatabaseSecret{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		// Re-enqueue siblings that share spec.secretName when any DatabaseSecret
		// in the namespace is created, deleted, or has a spec change. This lets
		// a loser CR recover automatically once the older claimant is removed or
		// rebinds to a different secret name; without this watch, a CR stuck in
		// SecretConflict would never be re-reconciled (its own spec hasn't changed).
		// GenerationChangedPredicate filters out status-only updates so the
		// fan-out doesn't run on every status patch.
		Watches(&dbaasv1.DatabaseSecret{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueSiblingsBySecretName),
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
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

// enqueueSiblingsBySecretName re-enqueues every DatabaseSecret in the same
// namespace that shares spec.secretName with the given object, excluding the
// object itself. It fires on create/update/delete of any DatabaseSecret so that
// CRs sitting in SecretConflict can recover automatically once the older
// claimant is removed or rebinds.
func (r *DatabaseSecretReconciler) enqueueSiblingsBySecretName(ctx context.Context, obj client.Object) []reconcile.Request {
	ds, ok := obj.(*dbaasv1.DatabaseSecret)
	if !ok || ds.Spec.SecretName == "" {
		return nil
	}
	list := &dbaasv1.DatabaseSecretList{}
	if err := r.List(ctx, list,
		client.InNamespace(ds.Namespace),
		client.MatchingFields{secretNameIndex: ds.Spec.SecretName},
	); err != nil {
		log.ErrorC(ctx, "enqueueSiblingsBySecretName: list DatabaseSecrets in %s: %v", ds.Namespace, err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		if list.Items[i].UID == ds.UID {
			continue
		}
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}

func (r *DatabaseSecretReconciler) buildOwnedSecret(
	owner *dbaasv1.DatabaseSecret,
	data map[string][]byte,
) (*corev1.Secret, error) {
	sec := &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      owner.Spec.SecretName,
			Namespace: owner.Namespace,
			Labels: map[string]string{
				"app.kubernetes.io/managed-by": "dbaas-operator",
				"app.kubernetes.io/name":       owner.Labels["app.kubernetes.io/name"],
			},
		},
		Type: corev1.SecretTypeOpaque,
		Data: data,
	}

	if err := ctrl.SetControllerReference(owner, sec, r.Scheme); err != nil {
		return nil, err
	}

	return sec, nil
}
