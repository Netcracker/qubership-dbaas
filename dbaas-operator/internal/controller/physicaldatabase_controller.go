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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=physicaldatabases,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=physicaldatabases/status,verbs=get;update;patch
//
// Secret access is granted by a namespaced Role + RoleBinding in the namespace of
// each PhysicalDatabase, so there is no cluster-wide secrets RBAC marker here.

import (
	"context"
	"fmt"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

const (
	// physicalDatabaseDefaultResync is the fallback re-reconcile period
	// used when ResyncInterval is left zero. The re-sync picks up a rotated adapter
	// password and restores a registration changed outside the resource.
	physicalDatabaseDefaultResync = 10 * time.Minute

	// physicalDatabaseAddressIndex and physicalDatabaseIDIndex are the field-index
	// keys used to find PhysicalDatabase resources that claim the same adapter or
	// the same physical database. The identifier is known only after the adapter
	// probe, so it is indexed from the status.
	physicalDatabaseAddressIndex = "spec.adapterAddress"
	physicalDatabaseIDIndex      = "status.physicalDatabaseId"

	// adapterSecretUsernameKey and adapterSecretPasswordKey are the fixed keys of
	// the Secret referenced by spec.credentialsSecretRef.
	adapterSecretUsernameKey = "username"
	adapterSecretPasswordKey = "password"

	// registrationStatusRunning is the wire status of every registration the
	// operator sends. With "running" the aggregator skips its handshake, which
	// would call the sender back; the operator probes the adapter itself instead.
	registrationStatusRunning = "running"

	// registrationAPIVersion is the adapter API version the operator always
	// declares: the aggregator creates its v2 adapter client only for exactly "v2".
	registrationAPIVersion = "v2"

	msgRoleMigrationStarted = "dbaas-aggregator parked the registration until a role migration of existing " +
		"logical databases completes; the operator polls until the aggregator answers 200"
)

// PhysicalDatabaseReconciler reconciles PhysicalDatabase objects.
//
// On every reconcile it checks that no older resource claims the same adapter,
// reads the adapter credentials from the referenced Secret, asks the adapter
// which physical database it serves, checks that no older resource reports the
// same physical database, and registers it with dbaas-aggregator.
type PhysicalDatabaseReconciler struct {
	client.Client
	Scheme      *runtime.Scheme
	Aggregator  *aggregatorclient.AggregatorClient
	Adapter     *aggregatorclient.AdapterClient
	Recorder    record.EventRecorder
	MyNamespace string

	// ResyncInterval re-reconciles each PhysicalDatabase periodically,
	// so a rotated adapter password is picked up without a Secret watch and a
	// registration changed outside the resource is restored. Defaults to
	// physicalDatabaseDefaultResync when zero.
	ResyncInterval time.Duration

	// pollBackoff spaces the re-registrations that follow a 202 while the role
	// migration runs.
	pollBackoff pollBackoffTracker
}

func (r *PhysicalDatabaseReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	pd := &dbaasv1.PhysicalDatabase{}
	if err := r.Get(ctx, req.NamespacedName, pd); err != nil {
		if apierrors.IsNotFound(err) {
			r.pollBackoff.forget(req.NamespacedName)
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	if !isEligibleForOperator(ctx, pd.Spec.OperatorNamespace, r.MyNamespace,
		pd.Namespace, pd.Name, "PhysicalDatabase") {
		return ctrl.Result{}, nil
	}
	// Periodic resyncs, refresh annotations, and sibling changes are also counted as spec_change.
	recordReconcileTrigger(controllerPDR, triggerSpecChange)

	original := pd.DeepCopy()
	defer func() {
		// 202 ends the reconcile without an error but is not terminal, so
		// observedGeneration stays behind until the aggregator holds this spec.
		patchStatusOnExit(ctx, r.Status(), pd, original, &retErr,
			func(p *dbaasv1.PhysicalDatabase, _ error) bool {
				return isTerminal(p.Status.Conditions, p.Generation)
			},
			"PhysicalDatabase")
	}()

	pd.Status.Phase = dbaasv1.PhaseProcessing

	if res, done, err := r.checkConflict(ctx, pd, physicalDatabaseAddressIndex, "adapterAddress",
		pd.Spec.AdapterAddress, requestID); done {
		return res, err
	}

	creds, err := r.readAdapterCredentials(ctx, pd)
	if err != nil {
		log.ErrorC(ctx, "Failed to read adapter credentials Secret: %v", err)
		dbaasSecretResolutionErrorsTotal.WithLabelValues(pd.Namespace, secretResolutionReason(err)).Inc()
		markTransientFailure(&pd.Status.Phase, &pd.Status.Conditions, pd.Generation, EventReasonSecretError, err.Error())
		r.Recorder.Eventf(pd, corev1.EventTypeWarning, EventReasonSecretError,
			"failed to read adapter credentials Secret: %s (requestId=%s)", err, requestID)
		return ctrl.Result{}, err // requeue with backoff
	}

	pd.Status.LastRequestID = requestID
	address := pd.Spec.AdapterAddress

	probeStart := time.Now()
	info, err := r.Adapter.GetPhysicalDatabase(ctx, address, creds)
	recordAdapterCall(controllerPDR, operationProbeAdapter, probeStart, err)
	if err != nil {
		log.ErrorC(ctx, "Failed to probe dbaas adapter address=%s: %v", address, err)
		return handleAdapterError(&pd.Status.Phase, &pd.Status.Conditions, pd.Generation, r.Recorder, pd, err, requestID)
	}
	setAdapterStatus(&pd.Status, info)

	if res, done, err := r.checkConflict(ctx, pd, physicalDatabaseIDIndex, "physicalDatabaseId",
		info.PhysicalDatabaseID, requestID); done {
		return res, err
	}

	aggStart := time.Now()
	accepted, err := r.Aggregator.RegisterPhysicalDatabase(ctx, info.Type, info.PhysicalDatabaseID,
		buildPhysicalDatabaseRegistrationRequest(pd, info, creds))
	recordAggregatorCall(controllerPDR, operationRegisterPDR, aggStart, err)
	if err != nil {
		log.ErrorC(ctx, "Failed to register physical database in dbaas-aggregator: %v", err)
		return handleAggregatorError(&pd.Status.Phase, &pd.Status.Conditions, pd.Generation, r.Recorder, pd, err, requestID)
	}

	if accepted {
		// The aggregator runs the migration; repeating the registration is how the
		// operator learns that it has completed: the aggregator then answers 200.
		log.InfoC(ctx, "Registration parked for role migration type=%s physicalDatabaseId=%s",
			info.Type, info.PhysicalDatabaseID)
		markRoleMigrationStarted(pd)
		return r.pollBackoff.schedule(pd), nil
	}

	r.pollBackoff.forgetObject(pd)
	log.InfoC(ctx, "Registered physical database type=%s physicalDatabaseId=%s",
		info.Type, info.PhysicalDatabaseID)
	markSucceeded(&pd.Status.Phase, &pd.Status.Conditions, pd.Generation, EventReasonPhysicalDatabaseRegistered)
	r.Recorder.Eventf(pd, corev1.EventTypeNormal, EventReasonPhysicalDatabaseRegistered,
		"registered with dbaas-aggregator (type=%s, physicalDatabaseId=%s)", info.Type, info.PhysicalDatabaseID)
	return ctrl.Result{RequeueAfter: r.ResyncInterval}, nil
}

// checkConflict marks pd as RegistrationConflict when an older resource of this
// operator claims the same value under index. done reports that the reconcile
// must stop and return res and err.
//
// The aggregator keys a registration by physicalDatabaseId alone and accepts a
// scheme-only address change, so two resources describing the same adapter
// would both report Succeeded while overwriting each other on every re-sync.
func (r *PhysicalDatabaseReconciler) checkConflict(
	ctx context.Context, pd *dbaasv1.PhysicalDatabase, index, field, value, requestID string,
) (res ctrl.Result, done bool, err error) {
	older, err := r.olderClaimant(ctx, pd, index, value)
	if err != nil {
		return ctrl.Result{}, true, err
	}
	if older == nil {
		return ctrl.Result{}, false, nil
	}
	r.pollBackoff.forgetObject(pd)
	msg := fmt.Sprintf("PhysicalDatabase %s/%s already claims %s %q (older claimant wins)",
		older.Namespace, older.Name, field, value)
	log.InfoC(ctx, "Detected registration conflict name=%s reason=%s", pd.Name, msg)
	markPermanentFailure(&pd.Status.Phase, &pd.Status.Conditions, pd.Generation, EventReasonRegistrationConflict, msg)
	r.Recorder.Eventf(pd, corev1.EventTypeWarning, EventReasonRegistrationConflict, "%s (requestId=%s)", msg, requestID)
	return ctrl.Result{}, true, nil
}

// olderClaimant returns the oldest other PhysicalDatabase of this operator
// whose index value equals value and that was created before pd, or nil when
// pd is the oldest claimant.
func (r *PhysicalDatabaseReconciler) olderClaimant(
	ctx context.Context, pd *dbaasv1.PhysicalDatabase, index, value string,
) (*dbaasv1.PhysicalDatabase, error) {
	// Both the identifier and the address are global in the aggregator, so
	// siblings are listed in every namespace.
	var siblings dbaasv1.PhysicalDatabaseList
	if err := r.List(ctx, &siblings, client.MatchingFields{index: value}); err != nil {
		return nil, err
	}
	var oldest *dbaasv1.PhysicalDatabase
	for i := range siblings.Items {
		sib := &siblings.Items[i]
		if sib.UID == pd.UID {
			continue
		}
		// The manager cache holds only resources assigned to this operator; the
		// check keeps the rule explicit should that cache filter ever change.
		if sib.Spec.OperatorNamespace != pd.Spec.OperatorNamespace {
			continue
		}
		if isOlderClaimant(sib, pd) && (oldest == nil || isOlderClaimant(sib, oldest)) {
			oldest = sib
		}
	}
	return oldest, nil
}

// readAdapterCredentials reads the adapter's Basic Auth pair from the fixed
// "username" and "password" keys of the Secret referenced by
// spec.credentialsSecretRef.
func (r *PhysicalDatabaseReconciler) readAdapterCredentials(
	ctx context.Context, pd *dbaasv1.PhysicalDatabase,
) (aggregatorclient.AdapterCredentials, error) {
	name := pd.Spec.CredentialsSecretRef.Name
	secret := &corev1.Secret{}
	if err := r.Get(ctx, types.NamespacedName{Namespace: pd.Namespace, Name: name}, secret); err != nil {
		reason := secretReasonReadFailed
		switch {
		case apierrors.IsNotFound(err):
			reason = secretReasonNotFound
		case apierrors.IsForbidden(err):
			reason = secretReasonForbidden
		}
		return aggregatorclient.AdapterCredentials{}, &secretResolutionError{
			reason: reason,
			err:    fmt.Errorf("get Secret %q: %w", name, err),
		}
	}

	values := make(map[string]string, 2)
	for _, key := range []string{adapterSecretUsernameKey, adapterSecretPasswordKey} {
		val, ok := secret.Data[key]
		if !ok {
			return aggregatorclient.AdapterCredentials{}, &secretResolutionError{
				reason: secretReasonKeyMissing,
				err:    fmt.Errorf("adapter credentials Secret %q missing key %q", name, key),
			}
		}
		if len(val) == 0 {
			return aggregatorclient.AdapterCredentials{}, &secretResolutionError{
				reason: secretReasonKeyEmpty,
				err:    fmt.Errorf("adapter credentials Secret %q key %q is empty", name, key),
			}
		}
		values[key] = string(val)
	}
	return aggregatorclient.AdapterCredentials{
		Username: values[adapterSecretUsernameKey],
		Password: values[adapterSecretPasswordKey],
	}, nil
}

// setAdapterStatus copies the adapter's answer into the status.
func setAdapterStatus(status *dbaasv1.PhysicalDatabaseStatus, info *aggregatorclient.AdapterPhysicalDatabase) {
	status.PhysicalDatabaseID = info.PhysicalDatabaseID
	status.Type = info.Type
	status.Labels = info.Labels
	status.SupportedRoles = info.SupportedRoles
	status.Features = info.Features
	status.ReadOnlyHost = info.ReadOnlyHost
	status.APIVersions = nil
	if info.APIVersions != nil {
		specs := make([]dbaasv1.AdapterAPIVersionSpec, 0, len(info.APIVersions.Specs))
		for _, s := range info.APIVersions.Specs {
			specs = append(specs, dbaasv1.AdapterAPIVersionSpec(s))
		}
		status.APIVersions = &dbaasv1.AdapterAPIVersions{Specs: specs}
	}
}

// buildPhysicalDatabaseRegistrationRequest combines the spec, the adapter's
// answer, and the adapter credentials into the aggregator registration request.
func buildPhysicalDatabaseRegistrationRequest(
	pd *dbaasv1.PhysicalDatabase, info *aggregatorclient.AdapterPhysicalDatabase, creds aggregatorclient.AdapterCredentials,
) *aggregatorclient.PhysicalDatabaseRegistrationRequest {
	return &aggregatorclient.PhysicalDatabaseRegistrationRequest{
		AdapterAddress:       pd.Spec.AdapterAddress,
		HTTPBasicCredentials: aggregatorclient.HTTPBasicCredentials{Username: creds.Username, Password: creds.Password},
		Labels:               info.Labels,
		Metadata: aggregatorclient.PhysicalDatabaseMetadata{
			APIVersion:     registrationAPIVersion,
			APIVersions:    info.APIVersions,
			SupportedRoles: info.SupportedRoles,
			Features:       info.Features,
			ROHost:         info.ReadOnlyHost,
		},
		Status: registrationStatusRunning,
	}
}

// markRoleMigrationStarted records the aggregator's 202: the registration is
// parked until a role migration completes. It is neither success nor failure,
// so Ready and Stalled are both False and observedGeneration does not advance.
func markRoleMigrationStarted(pd *dbaasv1.PhysicalDatabase) {
	pd.Status.Phase = dbaasv1.PhaseWaitingForDependency
	setCondition(&pd.Status.Conditions, pd.Generation,
		conditionTypeReady, metav1.ConditionFalse, ReasonRoleMigrationStarted, msgRoleMigrationStarted)
	setCondition(&pd.Status.Conditions, pd.Generation,
		conditionTypeStalled, metav1.ConditionFalse, ReasonRoleMigrationStarted, stalledMsgTransient)
}

// siblingClaimChangedPredicate passes the sibling events that can start or end
// a RegistrationConflict: a spec change (the address), or a change of
// status.physicalDatabaseId, which a probe can rewrite without any spec change.
// Other status writes are filtered out: every reconcile rewrites
// status.lastRequestId, and siblings would keep re-enqueueing each other.
//
// Only Update is customized; Create and Delete use predicate.Funcs defaults.
type siblingClaimChangedPredicate struct{ predicate.Funcs }

func (siblingClaimChangedPredicate) Update(e event.UpdateEvent) bool {
	oldPD, okOld := e.ObjectOld.(*dbaasv1.PhysicalDatabase)
	newPD, okNew := e.ObjectNew.(*dbaasv1.PhysicalDatabase)
	if !okOld || !okNew {
		return false
	}
	return oldPD.Generation != newPD.Generation ||
		oldPD.Status.PhysicalDatabaseID != newPD.Status.PhysicalDatabaseID
}

// SetupWithManager registers the field indexes and the watches.
// There is no Secret watch (the operator holds only namespaced Secret RBAC);
// a rotated password is picked up by the periodic re-sync or by changing
// AnnotationRefresh.
func (r *PhysicalDatabaseReconciler) SetupWithManager(mgr ctrl.Manager, config RateLimiterConfig) error {
	if r.ResyncInterval == 0 {
		r.ResyncInterval = physicalDatabaseDefaultResync
	}

	if err := indexPhysicalDatabases(context.Background(), mgr.GetFieldIndexer()); err != nil {
		return err
	}

	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.PhysicalDatabase{},
			builder.WithPredicates(specOrRefreshTriggerPredicate{})).
		// Re-enqueue the resources that claim the same adapter address or physical
		// database when any of them is created, deleted, or changes its claim, so a
		// younger resource in RegistrationConflict recovers once the older claimant
		// is gone. Its own spec has not changed, so nothing else would reconcile it.
		Watches(&dbaasv1.PhysicalDatabase{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueSiblings),
			builder.WithPredicates(siblingClaimChangedPredicate{})).
		WithOptions(config.controllerOptions()).
		Named("physicaldatabase").
		Complete(r)
}

// indexPhysicalDatabases registers the adapter address and physical database
// id indexes. The envtest suite calls it too, because its tests call Reconcile
// directly and never run SetupWithManager.
func indexPhysicalDatabases(ctx context.Context, indexer client.FieldIndexer) error {
	if err := indexer.IndexField(ctx, &dbaasv1.PhysicalDatabase{}, physicalDatabaseAddressIndex,
		func(obj client.Object) []string {
			return []string{obj.(*dbaasv1.PhysicalDatabase).Spec.AdapterAddress}
		}); err != nil {
		return err
	}
	return indexer.IndexField(ctx, &dbaasv1.PhysicalDatabase{}, physicalDatabaseIDIndex,
		func(obj client.Object) []string {
			if id := obj.(*dbaasv1.PhysicalDatabase).Status.PhysicalDatabaseID; id != "" {
				return []string{id}
			}
			return nil
		})
}

// enqueueSiblings re-enqueues every PhysicalDatabase that claims the same
// adapter address or physical database id as obj, excluding obj itself.
func (r *PhysicalDatabaseReconciler) enqueueSiblings(ctx context.Context, obj client.Object) []reconcile.Request {
	pd, ok := obj.(*dbaasv1.PhysicalDatabase)
	if !ok {
		return nil
	}
	seen := map[types.UID]bool{pd.UID: true}
	var reqs []reconcile.Request
	for index, value := range map[string]string{
		physicalDatabaseAddressIndex: pd.Spec.AdapterAddress,
		physicalDatabaseIDIndex:      pd.Status.PhysicalDatabaseID,
	} {
		if value == "" {
			continue
		}
		var siblings dbaasv1.PhysicalDatabaseList
		if err := r.List(ctx, &siblings, client.MatchingFields{index: value}); err != nil {
			log.ErrorC(ctx, "Failed to list PhysicalDatabase %s=%s: %v", index, value, err)
			continue
		}
		for i := range siblings.Items {
			if seen[siblings.Items[i].UID] {
				continue
			}
			seen[siblings.Items[i].UID] = true
			reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&siblings.Items[i])})
		}
	}
	return reqs
}
