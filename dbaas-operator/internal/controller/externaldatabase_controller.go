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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=externaldatabases,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=externaldatabases/status,verbs=get;update;patch
//
// Secret access is granted by a namespaced Role + RoleBinding provisioned alongside the
// NamespaceBinding (not a ClusterRole), so there is no cluster-wide secrets RBAC marker here.

import (
	"context"
	"fmt"
	"maps"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
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

// externalDatabaseDefaultResync is the fallback re-reconcile period used when
// ResyncInterval is left zero. The operator no longer watches Secrets, so a change
// to a referenced credentials Secret is picked up on the next periodic resync.
const externalDatabaseDefaultResync = 10 * time.Minute

// ExternalDatabaseReconciler reconciles ExternalDatabase objects.
//
// On every reconcile it assembles the registration request (reading credentials
// from Kubernetes Secrets), calls the dbaas-aggregator, and updates the CR status.
// Key outcomes are also emitted as Kubernetes Events so they appear in
// `kubectl describe externaldatabase <name>`.
type ExternalDatabaseReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
	Ownership  *ownership.OwnershipResolver

	// ResyncInterval re-reconciles each ExternalDatabase periodically so a change to a
	// referenced credentials Secret is eventually picked up without a Secret watch.
	// Defaults to externalDatabaseDefaultResync when zero.
	ResyncInterval time.Duration

	bindingTriggerTracker
}

func (r *ExternalDatabaseReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	edbKey := req.Namespace + "/" + req.Name
	edb := &dbaasv1.ExternalDatabase{}
	if err := r.Get(ctx, req.NamespacedName, edb); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(edbKey)
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// Classify the reconcile trigger for the metric: a spec change (default) or a
	// NamespaceBinding change. Periodic resyncs are also counted as spec_change.
	trigger := triggerSpecChange
	if r.consumeBindingTrigger(edbKey) {
		trigger = triggerNamespaceBindingChange
	}

	// Skip namespaces not owned by this operator instance.
	owned, result, err := checkOwnership(ctx, r.Ownership, edb.Namespace, edb.Name, "ExternalDatabase")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(edbKey)
		return result, nil
	}
	recordReconcileTrigger(controllerEDB, trigger)

	// Snapshot for the status patch at the end of reconcile.
	original := edb.DeepCopy()

	// Always patch status on exit, even if reconcile fails.
	// This ensures the CR reflects the actual outcome.
	defer func() {
		patchStatusOnExit(ctx, r.Status(), edb, original, &retErr,
			func(_ *dbaasv1.ExternalDatabase, retErr error) bool { return retErr == nil },
			"ExternalDatabase")
	}()

	// Mark as Processing while we work.
	// Conditions are NOT cleared here — setCondition upserts each type in place,
	// preserving LastTransitionTime when Status has not changed.
	// This makes conditions durable API state across reconcile cycles.
	edb.Status.Phase = dbaasv1.PhaseProcessing

	// Validate that classifier.namespace, if set, matches the CR's own namespace.
	// A mismatch is a permanent misconfiguration — no retry.
	if ns := edb.Spec.Classifier.Namespace; ns != "" && ns != edb.Namespace {
		return invalidSpec(ctx, &edb.Status.Phase, &edb.Status.Conditions, edb.Generation,
			r.Recorder, edb,
			fmt.Sprintf("spec.classifier.namespace %q must match metadata.namespace %q", ns, edb.Namespace))
	}

	// extraKeys must not shadow the typed classifier fields — a collision is a
	// spec mistake (the typed field would win and the extraKey be dropped).
	if reserved := dbaasv1.ReservedExtraKeys(edb.Spec.Classifier); len(reserved) > 0 {
		return invalidSpec(ctx, &edb.Status.Phase, &edb.Status.Conditions, edb.Generation,
			r.Recorder, edb,
			fmt.Sprintf("spec.classifier.extraKeys must not contain the reserved keys %v — they are owned by the typed classifier fields", reserved))
	}

	// Validate that all keys[].name values are unique within each connectionProperties entry.
	// Duplicate names would silently overwrite each other in the aggregator request.
	for i, cp := range edb.Spec.ConnectionProperties {
		if cp.CredentialsSecretRef == nil {
			continue
		}
		seen := make(map[string]struct{}, len(cp.CredentialsSecretRef.Keys))
		for _, k := range cp.CredentialsSecretRef.Keys {
			if _, dup := seen[k.Name]; dup {
				return invalidSpec(ctx, &edb.Status.Phase, &edb.Status.Conditions, edb.Generation,
					r.Recorder, edb,
					fmt.Sprintf("spec.connectionProperties[%d].credentialsSecretRef.keys contains duplicate name %q", i, k.Name))
			}
			seen[k.Name] = struct{}{}
		}
	}

	// Build the flat-map request, resolving any Secret references.
	aggReq, err := r.buildRequest(ctx, edb)
	if err != nil {
		log.ErrorC(ctx, "failed to build registration request: %v", err)
		dbaasSecretResolutionErrorsTotal.WithLabelValues(edb.Namespace, secretResolutionReason(err)).Inc()
		markTransientFailure(&edb.Status.Phase, &edb.Status.Conditions, edb.Generation,
			EventReasonSecretError, err.Error())
		r.Recorder.Eventf(edb, corev1.EventTypeWarning, EventReasonSecretError,
			"failed to read credentials Secret: %s (requestId=%s)", err, requestID)
		return ctrl.Result{}, err // requeue with backoff
	}

	// The namespace used in the aggregator URL comes from the classifier; fall
	// back to the CR's own namespace if the classifier does not contain one.
	namespace := resolveAggregatorNamespace(edb)

	// Call the aggregator.
	edb.Status.LastRequestID = requestID
	aggStart := time.Now()
	aggErr := r.Aggregator.RegisterExternalDatabase(ctx, namespace, aggReq)
	recordAggregatorCall(controllerEDB, operationRegisterEDB, aggStart, aggErr)
	if aggErr != nil {
		log.ErrorC(ctx, "failed to register external database in dbaas-aggregator: %v", aggErr)
		return handleAggregatorError(&edb.Status.Phase, &edb.Status.Conditions, edb.Generation, r.Recorder, edb, aggErr, requestID)
	}

	log.InfoC(ctx, "external database registered successfully. type: %v, dbName: %v", edb.Spec.Type, edb.Spec.DbName)
	markSucceeded(&edb.Status.Phase, &edb.Status.Conditions, edb.Generation, EventReasonDatabaseRegistered)
	r.Recorder.Eventf(edb, corev1.EventTypeNormal, EventReasonDatabaseRegistered,
		"registered with dbaas-aggregator (type=%s, dbName=%s)", edb.Spec.Type, edb.Spec.DbName)
	// Periodically re-reconcile so a change to a referenced credentials Secret is picked up
	// without a Secret watch (the operator holds only namespaced Secret RBAC).
	return ctrl.Result{RequeueAfter: r.ResyncInterval}, nil
}

// buildRequest assembles an ExternalDatabaseRequest from the CR spec.
// For each ConnectionProperty that has a credentialsSecretRef it reads the
// referenced Secret and injects the mapped keys into the flat map.
func (r *ExternalDatabaseReconciler) buildRequest(
	ctx context.Context,
	edb *dbaasv1.ExternalDatabase,
) (*aggregatorclient.ExternalDatabaseRequest, error) {
	connProps, err := r.buildConnectionProperties(ctx, edb)
	if err != nil {
		return nil, err
	}

	return &aggregatorclient.ExternalDatabaseRequest{
		// Serialize the classifier with dbaasv1.ClassifierFlatMap — the same helper
		// the InternalDatabase and DatabaseSecretClaim paths use. It keeps customKeys
		// as a nested "customKeys" object, which is the canonical dbaas-aggregator
		// classifier shape: the aggregator stores the classifier verbatim and reads
		// classifier.customKeys.* as a nested map, so an externally registered
		// database is found by the same classifier dbaas-client consumers use.
		// EffectiveClassifier defaults classifier.namespace to metadata.namespace
		// when omitted — the aggregator requires it (isValidClassifierV3) and the
		// controller already validates that a non-empty value equals metadata.namespace.
		Classifier:                 dbaasv1.ClassifierFlatMap(dbaasv1.EffectiveClassifier(edb.Spec.Classifier, edb.Namespace)),
		Type:                       edb.Spec.Type,
		DbName:                     edb.Spec.DbName,
		ConnectionProperties:       connProps,
		UpdateConnectionProperties: true,
	}, nil
}

func resolveAggregatorNamespace(edb *dbaasv1.ExternalDatabase) string {
	if edb.Spec.Classifier.Namespace != "" {
		return edb.Spec.Classifier.Namespace
	}
	return edb.Namespace
}

func (r *ExternalDatabaseReconciler) buildConnectionProperties(
	ctx context.Context,
	edb *dbaasv1.ExternalDatabase,
) ([]map[string]string, error) {
	connProps := make([]map[string]string, 0, len(edb.Spec.ConnectionProperties))

	for i, cp := range edb.Spec.ConnectionProperties {
		flat := make(map[string]string, len(cp.ExtraProperties)+1) // +1 for "role"

		// Extra properties are merged first so that typed fields and resolved
		// Secret credentials always win on key collisions.
		maps.Copy(flat, cp.ExtraProperties)
		flat["role"] = cp.Role

		if err := r.applySecretCredentials(ctx, edb.Namespace, i, cp, flat); err != nil {
			return nil, err
		}

		connProps = append(connProps, flat)
	}

	return connProps, nil
}

func (r *ExternalDatabaseReconciler) applySecretCredentials(
	ctx context.Context,
	namespace string,
	index int,
	cp dbaasv1.ConnectionProperty,
	flat map[string]string,
) error {
	if cp.CredentialsSecretRef == nil {
		return nil
	}

	ref := cp.CredentialsSecretRef
	secret := &corev1.Secret{}
	if err := r.Get(ctx, types.NamespacedName{Namespace: namespace, Name: ref.Name}, secret); err != nil {
		reason := secretReasonReadFailed
		switch {
		case apierrors.IsNotFound(err):
			reason = secretReasonNotFound
		case apierrors.IsForbidden(err):
			reason = secretReasonForbidden
		}
		return &secretResolutionError{
			reason: reason,
			err: fmt.Errorf(
				"connectionProperties[%d]: get Secret %q: %w",
				index, ref.Name, err),
		}
	}

	// Defense-in-depth duplicate name check — CRD CEL validation should catch this
	// first, but we guard here too in case validation is bypassed.
	seen := make(map[string]string, len(ref.Keys))
	for _, km := range ref.Keys {
		if prevKey, dup := seen[km.Name]; dup {
			return fmt.Errorf(
				"connectionProperties[%d]: credentialsSecretRef has duplicate target key %q (from Secret keys %q and %q)",
				index, km.Name, prevKey, km.Key)
		}
		seen[km.Name] = km.Key

		val, ok := secret.Data[km.Key]
		if !ok {
			return &secretResolutionError{
				reason: secretReasonKeyMissing,
				err: fmt.Errorf(
					"connectionProperties[%d]: Secret %q missing key %q",
					index, ref.Name, km.Key),
			}
		}
		if len(val) == 0 {
			return &secretResolutionError{
				reason: secretReasonKeyEmpty,
				err: fmt.Errorf(
					"connectionProperties[%d]: Secret %q key %q is empty",
					index, ref.Name, km.Key),
			}
		}
		flat[km.Name] = string(val)
	}
	return nil
}

// specOrRefreshTriggerPredicate fires a reconcile on (a) a spec change
// (generation bump) or (b) a change to the refresh annotation (AnnotationRefresh).
// The ExternalDatabase controller does not watch Secrets, so a referenced
// credential Secret change is normally only picked up on the periodic resync;
// touching the refresh annotation forces an immediate reconcile — re-read the
// Secret and re-register with dbaas-aggregator — without a spec change. Plain
// GenerationChangedPredicate is not enough: an annotation change does not bump
// generation and would otherwise be filtered out.
//
// Create and Delete fall through to the embedded predicate.Funcs defaults
// (both return true), preserving standard behavior for new and removed CRs.
// Only Update is customized.
type specOrRefreshTriggerPredicate struct{ predicate.Funcs }

func (specOrRefreshTriggerPredicate) Update(e event.UpdateEvent) bool {
	if e.ObjectOld == nil || e.ObjectNew == nil {
		return false
	}
	if e.ObjectOld.GetGeneration() != e.ObjectNew.GetGeneration() {
		return true
	}
	return e.ObjectOld.GetAnnotations()[dbaasv1.AnnotationRefresh] !=
		e.ObjectNew.GetAnnotations()[dbaasv1.AnnotationRefresh]
}

// SetupWithManager sets up the controller with the Manager.
// The For-predicate (specOrRefreshTriggerPredicate) reconciles on spec changes
// (metadata.generation) and on a change to the AnnotationRefresh annotation — the
// latter is a manual escape hatch to apply a referenced-Secret change at once
// instead of waiting for the resync.
//
// There is intentionally no Secret watch: the operator holds only namespaced Secret
// RBAC (no cluster-wide list/watch), so credential-Secret changes are picked up by the
// periodic resync (ResyncInterval) via RequeueAfter on a successful reconcile, or
// immediately via the refresh annotation above.
//
// opts allows callers to customize the controller's behavior — most notably
// the RateLimiter, which controls the exponential backoff applied when
// Reconcile returns an error (BackingOff phase).  Pass
// ctrlcontroller.Options{} to keep the controller-runtime defaults.
func (r *ExternalDatabaseReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	if r.ResyncInterval == 0 {
		r.ResyncInterval = externalDatabaseDefaultResync
	}

	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.ExternalDatabase{},
			builder.WithPredicates(specOrRefreshTriggerPredicate{})).
		// Re-enqueue all ExternalDatabases in a namespace when its NamespaceBinding
		// is created or updated, so existing CRs are reconciled without waiting for
		// a spec change.
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueForBinding)).
		WithOptions(opts).
		Named("externaldatabase").
		Complete(r)
}

// enqueueForBinding maps an NamespaceBinding event to reconcile requests for
// all ExternalDatabases that live in the same namespace.
func (r *ExternalDatabaseReconciler) enqueueForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	return enqueueForBindingList(ctx, r.Client, &dbaasv1.ExternalDatabaseList{}, obj.GetNamespace(),
		func(o client.Object) { r.stampBindingTrigger(o.GetNamespace() + "/" + o.GetName()) })
}
