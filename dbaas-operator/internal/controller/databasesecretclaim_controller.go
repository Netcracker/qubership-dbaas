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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasesecretclaims,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=databasesecretclaims/status,verbs=get;update;patch
//
// Secret access is granted by a namespaced Role + RoleBinding provisioned alongside the
// NamespaceBinding (not a ClusterRole), so there is no cluster-wide secrets RBAC marker here.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"sync"
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
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

// secretNameIndex is the field index key for DatabaseSecretClaim.Spec.SecretName,
// used to resolve sibling conflicts in O(1) instead of a full namespace scan.
const secretNameIndex = "spec.secretName"

// DatabaseSecretClaimReconciler reconciles DatabaseSecretClaim objects.
//
// On every reconcile it:
//  1. Runs a pre-flight uniqueness check on the target Secret name.
//  2. Calls POST /api/v3/dbaas/{ns}/databases/get-by-classifier/{type} on the aggregator.
//  3. Re-checks the target Secret for ownership conflicts (race guard).
//  4. Creates or updates the core v1.Secret with the returned connectionProperties.
type DatabaseSecretClaimReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver

	bindingTriggerTracker
	triggerMu             sync.Mutex
	siblingTriggerStamps  map[string]struct{}
	rotationTriggerValues map[string]string
}

func (r *DatabaseSecretClaimReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	s := &dbaasv1.DatabaseSecretClaim{}
	if err := r.Get(ctx, req.NamespacedName, s); err != nil {
		if apierrors.IsNotFound(err) {
			key := req.Namespace + "/" + req.Name
			r.clearBindingTrigger(key)
			r.clearSiblingTrigger(key)
			r.clearRotationTrigger(key)
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := s.Namespace + "/" + s.Name

	owned, result, err := checkOwnership(ctx, r.Ownership, s.Namespace, s.Name, "DatabaseSecretClaim")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		if r.Ownership.GetState(s.Namespace) == ownership.Foreign {
			r.clearBindingTrigger(key)
			r.clearSiblingTrigger(key)
			r.clearRotationTrigger(key)
		}
		return result, nil
	}
	trigger := r.triggerForSecretClaim(key, s)
	recordReconcileTrigger(controllerDSC, trigger)

	original := s.DeepCopy()
	defer func() {
		// Stamp observedGeneration on terminal states only. Successful
		// reconciles now carry a safety-net RequeueAfter, so the result's
		// requeue delay can no longer distinguish "done" from "retrying";
		// gate on the phase instead. Succeeded and InvalidConfiguration are
		// terminal (the generation has been fully processed); BackingOff is
		// not (still polling on a transient error).
		patchStatusOnExit(ctx, r.Status(), s, original, &retErr,
			func(obj *dbaasv1.DatabaseSecretClaim, retErr error) bool {
				return retErr == nil &&
					(obj.Status.Phase == dbaasv1.PhaseSucceeded ||
						obj.Status.Phase == dbaasv1.PhaseInvalidConfiguration)
			},
			"DatabaseSecretClaim")
	}()

	s.Status.Phase = dbaasv1.PhaseProcessing

	// ── Pre-flight validations (spec + sibling/Secret ownership) ──────────────
	if res, stop, err := r.preflightValidate(ctx, s); stop {
		return res, err
	}

	// ── Step 7: call aggregator ───────────────────────────────────────────────
	aggReq := &aggregatorclient.GetByClassifierRequest{
		Classifier:    dbaasv1.ClassifierFlatMap(dbaasv1.EffectiveClassifier(s.Spec.Classifier, s.Namespace)),
		OriginService: s.Labels["app.kubernetes.io/name"],
		UserRole:      s.Spec.UserRole,
	}
	aggStart := time.Now()
	dbResp, err := r.Aggregator.GetDatabaseByClassifier(ctx, s.Namespace, s.Spec.Type, aggReq)
	recordAggregatorCall(controllerDSC, operationGetDatabase, aggStart, err)
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
	secretData, err := buildSecretData(s, dbResp)
	if err != nil {
		return ctrl.Result{}, err
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
func (r *DatabaseSecretClaimReconciler) preflightValidate(
	ctx context.Context,
	s *dbaasv1.DatabaseSecretClaim,
) (ctrl.Result, bool, error) {
	if ns := s.Spec.Classifier.Namespace; ns != "" && ns != s.Namespace {
		res, err := invalidSpec(ctx, &s.Status.Phase, &s.Status.Conditions, s.Generation,
			r.Recorder, s,
			fmt.Sprintf("spec.classifier.namespace %q must match metadata.namespace %q",
				ns, s.Namespace))
		return res, true, err
	}

	// extraKeys must not shadow the typed classifier fields — a collision is a
	// spec mistake (the typed field would win and the extraKey be dropped).
	if reserved := dbaasv1.ReservedExtraKeys(s.Spec.Classifier); len(reserved) > 0 {
		res, err := invalidSpec(ctx, &s.Status.Phase, &s.Status.Conditions, s.Generation,
			r.Recorder, s,
			fmt.Sprintf("spec.classifier.extraKeys must not contain the reserved keys %v — they are owned by the typed classifier fields", reserved))
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
	var siblings dbaasv1.DatabaseSecretClaimList
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
				fmt.Sprintf("another DatabaseSecretClaim %q in namespace %q already claims secretName %q (older claimant wins)",
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
func (r *DatabaseSecretClaimReconciler) writeSecret(
	ctx context.Context,
	s *dbaasv1.DatabaseSecretClaim,
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
		r.markSecretSucceeded(s, requestID, EventReasonSecretCreated,
			"Secret %q created with connection properties (requestId=%s)")
		return ctrl.Result{RequeueAfter: secretRotationSafetyNetInterval}, nil
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
				r.markSecretSucceeded(s, requestID, EventReasonSecretCreated,
					"Secret %q created after deletion race (requestId=%s)")
				return ctrl.Result{RequeueAfter: secretRotationSafetyNetInterval}, nil
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
// we have just confirmed is already owned by s (the Step 9.3 ownerConflict
// check passed). It recreates the Secret if the Update fails with NotFound (GC
// raced between fetch and update).
//
// When the existing Secret already carries exactly the credentials and managed
// labels we would write, the Update is skipped entirely: a rotation-triggered
// reconcile that finds unchanged content must not churn the Secret, since every
// rewrite wakes mounting pods via the kubelet's secret sync.
//
// Only a change to connectionProperties.json is treated as a rotation — it
// stamps Status.LastRotatedAt and emits SecretRotated. A metadata.json or label
// backfill (credentials unchanged) still rewrites the Secret but reports plain
// success (SecretCreated reason, no LastRotatedAt, no SecretRotated event), so
// the rotation timestamp stays faithful to its connection-properties contract.
func (r *DatabaseSecretClaimReconciler) updateOwnedSecret(
	ctx context.Context,
	s *dbaasv1.DatabaseSecretClaim,
	existing *corev1.Secret,
	secretData map[string][]byte,
	requestID string,
) (ctrl.Result, error) {
	// No-op fast path: already in the desired state.
	if secretUpToDate(s, existing, secretData) {
		log.InfoC(ctx, "DatabaseSecretClaim already up-to-date, skipping Secret write name=%s secretName=%s", s.Name, s.Spec.SecretName)
		// Steady state: report the SecretUpToDate Ready reason, emit no event, and
		// do not advance LastRotatedAt — nothing actually changed. Using a neutral
		// reason (rather than reusing SecretCreated) keeps the Ready reason accurate
		// and avoids clobbering a prior SecretRotated reason on the next safety-net poll.
		markSucceeded(&s.Status.Phase, &s.Status.Conditions, s.Generation, ReasonSecretUpToDate)
		return ctrl.Result{RequeueAfter: secretRotationSafetyNetInterval}, nil
	}

	// Distinguish a credential change (rotation) from a metadata.json / label
	// backfill: only the former advances LastRotatedAt and emits SecretRotated.
	// existing.Data is the pre-update content; secretData is the desired content.
	credentialsChanged := !bytes.Equal(
		existing.Data[secretKeyConnectionProperties],
		secretData[secretKeyConnectionProperties])

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
				r.markSecretSucceeded(s, requestID, EventReasonSecretCreated,
					"Secret %q recreated after deletion (requestId=%s)")
				return ctrl.Result{RequeueAfter: secretRotationSafetyNetInterval}, nil
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
	// The Secret was written. Only stamp LastRotatedAt and emit SecretRotated
	// when the credentials actually changed. A metadata.json or label backfill
	// rewrites the Secret once but is not a rotation, so it must not advance the
	// rotation timestamp nor report SecretRotated.
	if !credentialsChanged {
		log.InfoC(ctx, "DatabaseSecretClaim Secret updated without credential change (metadata/label backfill) name=%s secretName=%s",
			s.Name, s.Spec.SecretName)
		markSucceeded(&s.Status.Phase, &s.Status.Conditions, s.Generation, ReasonSecretUpToDate)
		return ctrl.Result{RequeueAfter: secretRotationSafetyNetInterval}, nil
	}
	now := metav1.Now()
	s.Status.LastRotatedAt = &now
	r.markSecretSucceeded(s, requestID, EventReasonSecretRotated,
		"Secret %q updated with rotated connection properties (requestId=%s)")
	return ctrl.Result{RequeueAfter: secretRotationSafetyNetInterval}, nil
}

// secretUpToDate reports whether the existing Secret already carries exactly
// the connection-properties data and operator-managed labels that a write
// would set. The ownerReference is not checked here because updateOwnedSecret
// is only reached after the Step 9.3 ownerConflict check confirmed s controls
// the Secret.
func secretUpToDate(s *dbaasv1.DatabaseSecretClaim, existing *corev1.Secret, desired map[string][]byte) bool {
	if !maps.EqualFunc(existing.Data, desired, bytes.Equal) {
		return false
	}
	if existing.Labels["app.kubernetes.io/managed-by"] != "dbaas-operator" {
		return false
	}
	if existing.Labels["app.kubernetes.io/name"] != s.Labels["app.kubernetes.io/name"] {
		return false
	}
	return true
}

// markSecretSucceeded marks the CR Succeeded with the given Ready reason, emits
// a Normal event under that reason, and logs a confirmation line. reason is one
// of EventReasonSecretCreated (creation / recreation) or EventReasonSecretRotated
// (content changed). eventFormat must be a printf-style format string with two
// placeholders: the secret name and the request ID.
func (r *DatabaseSecretClaimReconciler) markSecretSucceeded(s *dbaasv1.DatabaseSecretClaim, requestID, reason, eventFormat string) {
	log.Infof("DatabaseSecretClaim reconciled successfully name=%s secretName=%s reason=%s", s.Name, s.Spec.SecretName, reason)
	markSucceeded(&s.Status.Phase, &s.Status.Conditions, s.Generation, reason)
	r.Recorder.Eventf(s, corev1.EventTypeNormal, reason, eventFormat, s.Spec.SecretName, requestID)
}

// ownerConflict returns true when the existing Secret is owned by a resource other than s,
// or has no owner at all. msg describes the conflict.
func (r *DatabaseSecretClaimReconciler) ownerConflict(s *dbaasv1.DatabaseSecretClaim, existing *corev1.Secret) (bool, string) {
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
func (r *DatabaseSecretClaimReconciler) markSecretConflict(ctx context.Context, s *dbaasv1.DatabaseSecretClaim, msg string) (ctrl.Result, error) {
	log.InfoC(ctx, "SecretConflict name=%s reason=%s", s.Name, msg)
	markPermanentFailure(&s.Status.Phase, &s.Status.Conditions, s.Generation, EventReasonSecretConflict, msg)
	r.Recorder.Eventf(s, corev1.EventTypeWarning, EventReasonSecretConflict, "%s", msg)
	return ctrl.Result{}, nil
}

// handleAggregatorErr maps aggregator errors to phase/conditions/events for DatabaseSecretClaim.
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
func (r *DatabaseSecretClaimReconciler) handleAggregatorErr(
	ctx context.Context,
	s *dbaasv1.DatabaseSecretClaim,
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

// Secret data keys written by the operator and consumed by dbaas-client.
const (
	// secretKeyConnectionProperties holds the connectionProperties map returned
	// by dbaas-aggregator (dynamic shape, dictated by the adapter).
	secretKeyConnectionProperties = "connectionProperties.json"
	// secretKeyMetadata holds the self-describing descriptor (classifier, type,
	// userRole) that lets dbaas-client match a mounted Secret to a request.
	secretKeyMetadata = "metadata.json"
)

// secretMetadata is the self-describing descriptor stored under
// secretKeyMetadata. dbaas-client matches a mounted Secret to a request by
// (classifier, type, userRole). The classifier is the same canonical flat map
// the operator sends to dbaas-aggregator — namespace defaulted to
// metadata.namespace, empty optional fields omitted — so both sides agree on
// the key. UserRole is the requested role (DatabaseSecretClaim.spec.userRole), not
// the role the aggregator resolved at runtime; it is omitted when empty.
//
// Id, Name, Namespace, and Settings mirror the aggregator's
// DatabaseResponseV3SingleCP so dbaas-client can reconstruct a full LogicalDb
// from a mounted Secret without a REST call. They are descriptive only (not
// part of the match key) and are omitted when empty; Id in particular may be
// empty because the aggregator returns it best-effort on a by-classifier lookup.
type secretMetadata struct {
	Classifier map[string]any `json:"classifier"`
	Type       string         `json:"type"`
	UserRole   string         `json:"userRole,omitempty"`
	Id         string         `json:"id,omitempty"`
	Name       string         `json:"name,omitempty"`
	Namespace  string         `json:"namespace,omitempty"`
	Settings   map[string]any `json:"settings,omitempty"`
}

// buildSecretData serializes the connectionProperties and the self-describing
// metadata descriptor into the two keys of the managed Secret. The descriptor
// classifier is canonicalized identically to the aggregator request (see
// ClassifierFlatMap / EffectiveClassifier) so dbaas-client can match it, and the
// id/name/namespace/settings fields mirror the aggregator response so the client
// can reconstruct a full LogicalDb from the mounted Secret.
func buildSecretData(s *dbaasv1.DatabaseSecretClaim, dbResp *aggregatorclient.DatabaseResponseSingleCP) (map[string][]byte, error) {
	connRaw, err := json.Marshal(dbResp.ConnectionProperties)
	if err != nil {
		return nil, fmt.Errorf("marshal connectionProperties: %w", err)
	}
	metaRaw, err := json.Marshal(secretMetadata{
		Classifier: dbaasv1.ClassifierFlatMap(dbaasv1.EffectiveClassifier(s.Spec.Classifier, s.Namespace)),
		Type:       s.Spec.Type,
		UserRole:   s.Spec.UserRole,
		Id:         dbResp.Id,
		Name:       dbResp.Name,
		Namespace:  dbResp.Namespace,
		Settings:   dbResp.Settings,
	})
	if err != nil {
		return nil, fmt.Errorf("marshal metadata: %w", err)
	}
	return map[string][]byte{
		secretKeyConnectionProperties: connRaw,
		secretKeyMetadata:             metaRaw,
	}, nil
}

// isOlderClaimant returns true when a was created strictly before b. On equal
// creationTimestamps it falls back to lexical UID comparison so the result is
// stable across both peers (otherwise a tie would leave both CRs as "younger"
// and neither would lose).
func isOlderClaimant(a, b *dbaasv1.DatabaseSecretClaim) bool {
	if a.CreationTimestamp.Before(&b.CreationTimestamp) {
		return true
	}
	if b.CreationTimestamp.Before(&a.CreationTimestamp) {
		return false
	}
	return string(a.UID) < string(b.UID)
}

// specOrRotationTriggerPredicate fires a reconcile on (a) a spec change
// (generation bump) or (b) a change to the rotation-trigger annotation that
// the rotation poller patches when dbaas-aggregator reports a credentials
// rotation. Plain GenerationChangedPredicate is not enough here: the poller
// only mutates an annotation, which does not bump generation, so a rotation
// would otherwise be filtered out and never reconciled.
//
// Create and Delete fall through to the embedded predicate.Funcs defaults
// (both return true), preserving the standard behaviour for new and removed
// CRs. Only Update is customised.
type specOrRotationTriggerPredicate struct{ predicate.Funcs }

func (specOrRotationTriggerPredicate) Update(e event.UpdateEvent) bool {
	if e.ObjectOld == nil || e.ObjectNew == nil {
		return false
	}
	if e.ObjectOld.GetGeneration() != e.ObjectNew.GetGeneration() {
		return true
	}
	return e.ObjectOld.GetAnnotations()[dbaasv1.AnnotationRotationTrigger] !=
		e.ObjectNew.GetAnnotations()[dbaasv1.AnnotationRotationTrigger]
}

// SetupWithManager sets up the controller with the Manager.
func (r *DatabaseSecretClaimReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	if err := mgr.GetFieldIndexer().IndexField(
		context.Background(),
		&dbaasv1.DatabaseSecretClaim{},
		secretNameIndex,
		func(obj client.Object) []string {
			return []string{obj.(*dbaasv1.DatabaseSecretClaim).Spec.SecretName}
		},
	); err != nil {
		return err
	}

	if err := mgr.GetFieldIndexer().IndexField(
		context.Background(),
		&dbaasv1.DatabaseSecretClaim{},
		dbaasv1.ClassifierTypeIndex,
		func(obj client.Object) []string {
			ds := obj.(*dbaasv1.DatabaseSecretClaim)
			// Default the classifier namespace to metadata.namespace so the index
			// key matches the always-namespaced classifier carried in the rotation
			// poller's changed-databases feed (see EffectiveClassifier).
			c := dbaasv1.EffectiveClassifier(ds.Spec.Classifier, ds.Namespace)
			return []string{dbaasv1.ClassifierIndexKey(c, ds.Spec.Type)}
		},
	); err != nil {
		return err
	}

	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DatabaseSecretClaim{},
			builder.WithPredicates(specOrRotationTriggerPredicate{})).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		// Re-enqueue siblings that share spec.secretName when any DatabaseSecretClaim
		// in the namespace is created, deleted, or has a spec change. This lets
		// a loser CR recover automatically once the older claimant is removed or
		// rebinds to a different secret name; without this watch, a CR stuck in
		// SecretConflict would never be re-reconciled (its own spec hasn't changed).
		// GenerationChangedPredicate filters out status-only updates so the
		// fan-out doesn't run on every status patch.
		Watches(&dbaasv1.DatabaseSecretClaim{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueSiblingsBySecretName),
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		WithOptions(opts).
		Named("databasesecretclaim").
		Complete(r)
}

func (r *DatabaseSecretClaimReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	return enqueueForBindingList(ctx, r.Client, &dbaasv1.DatabaseSecretClaimList{}, obj.GetNamespace(),
		func(o client.Object) { r.stampBindingTrigger(o.GetNamespace() + "/" + o.GetName()) })
}

// enqueueSiblingsBySecretName re-enqueues every DatabaseSecretClaim in the same
// namespace that shares spec.secretName with the given object, excluding the
// object itself. It fires on create/update/delete of any DatabaseSecretClaim so that
// CRs sitting in SecretConflict can recover automatically once the older
// claimant is removed or rebinds.
func (r *DatabaseSecretClaimReconciler) enqueueSiblingsBySecretName(ctx context.Context, obj client.Object) []reconcile.Request {
	ds, ok := obj.(*dbaasv1.DatabaseSecretClaim)
	if !ok || ds.Spec.SecretName == "" {
		return nil
	}
	list := &dbaasv1.DatabaseSecretClaimList{}
	if err := r.List(ctx, list,
		client.InNamespace(ds.Namespace),
		client.MatchingFields{secretNameIndex: ds.Spec.SecretName},
	); err != nil {
		log.ErrorC(ctx, "enqueueSiblingsBySecretName: list DatabaseSecretClaims in %s: %v", ds.Namespace, err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		if list.Items[i].UID == ds.UID {
			continue
		}
		r.stampSiblingTrigger(list.Items[i].Namespace + "/" + list.Items[i].Name)
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}

func (r *DatabaseSecretClaimReconciler) triggerForSecretClaim(key string, s *dbaasv1.DatabaseSecretClaim) string {
	switch {
	case r.consumeRotationTrigger(key, s.Annotations[dbaasv1.AnnotationRotationTrigger]):
		return triggerRotation
	case r.consumeBindingTrigger(key):
		return triggerNamespaceBindingChange
	case r.consumeSiblingTrigger(key):
		return triggerSiblingSecretClaim
	case s.Status.ObservedGeneration >= s.Generation && s.Status.Phase == dbaasv1.PhaseSucceeded:
		return triggerSafetyNet
	default:
		return triggerSpecChange
	}
}

func (r *DatabaseSecretClaimReconciler) stampSiblingTrigger(key string) {
	r.triggerMu.Lock()
	defer r.triggerMu.Unlock()
	if r.siblingTriggerStamps == nil {
		r.siblingTriggerStamps = make(map[string]struct{})
	}
	r.siblingTriggerStamps[key] = struct{}{}
}

func (r *DatabaseSecretClaimReconciler) consumeSiblingTrigger(key string) bool {
	r.triggerMu.Lock()
	defer r.triggerMu.Unlock()
	if _, ok := r.siblingTriggerStamps[key]; !ok {
		return false
	}
	delete(r.siblingTriggerStamps, key)
	return true
}

func (r *DatabaseSecretClaimReconciler) clearSiblingTrigger(key string) {
	r.triggerMu.Lock()
	defer r.triggerMu.Unlock()
	delete(r.siblingTriggerStamps, key)
}

func (r *DatabaseSecretClaimReconciler) consumeRotationTrigger(key, current string) bool {
	r.triggerMu.Lock()
	defer r.triggerMu.Unlock()
	if r.rotationTriggerValues == nil {
		r.rotationTriggerValues = make(map[string]string)
	}
	previous, seen := r.rotationTriggerValues[key]
	r.rotationTriggerValues[key] = current
	return seen && current != "" && current != previous
}

func (r *DatabaseSecretClaimReconciler) clearRotationTrigger(key string) {
	r.triggerMu.Lock()
	defer r.triggerMu.Unlock()
	delete(r.rotationTriggerValues, key)
}

func (r *DatabaseSecretClaimReconciler) buildOwnedSecret(
	owner *dbaasv1.DatabaseSecretClaim,
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
