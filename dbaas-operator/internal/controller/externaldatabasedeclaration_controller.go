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
	"fmt"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
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

// ExternalDatabaseDeclarationReconciler reconciles ExternalDatabaseDeclaration objects.
//
// On every reconcile it assembles the registration request (reading credentials
// from Kubernetes Secrets), calls the dbaas-aggregator, and updates the CR status.
// Key outcomes are also emitted as Kubernetes Events so they appear in
// `kubectl describe externaldatabasedeclaration <name>`.
type ExternalDatabaseDeclarationReconciler struct {
	client.Client
	Scheme     *runtime.Scheme
	Aggregator *aggregatorclient.AggregatorClient
	Recorder   record.EventRecorder
}

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=externaldatabasedeclarations,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=externaldatabasedeclarations/status,verbs=get;update;patch
// +kubebuilder:rbac:groups="",resources=secrets,verbs=get;list;watch
// +kubebuilder:rbac:groups="",resources=events,verbs=create;patch

func (r *ExternalDatabaseDeclarationReconciler) Reconcile(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	log := logf.FromContext(ctx)

	edb := &dbaasv1alpha1.ExternalDatabaseDeclaration{}
	if err := r.Get(ctx, req.NamespacedName, edb); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// Snapshot for the status patch at the end of reconcile.
	original := edb.DeepCopy()

	// Always patch status on exit, even if reconcile fails.
	// This ensures the CR reflects the actual outcome.
	defer func() {
		patchStatusOnExit(ctx, r.Status(), edb, original, &retErr,
			func(_ *dbaasv1alpha1.ExternalDatabaseDeclaration, retErr error) bool { return retErr == nil },
			"ExternalDatabaseDeclaration")
	}()

	// Mark as Processing while we work.
	// Conditions are NOT cleared here — setCondition upserts each type in place,
	// preserving LastTransitionTime when Status has not changed.
	// This makes conditions durable API state across reconcile cycles.
	edb.Status.Phase = dbaasv1alpha1.PhaseProcessing

	// Validate that classifier.namespace, if set, matches the CR's own namespace.
	// A mismatch is a permanent misconfiguration — no retry.
	if ns := edb.Spec.Classifier["namespace"]; ns != "" && ns != edb.Namespace {
		return invalidSpec(ctx, &edb.Status.Phase, &edb.Status.Conditions, edb.Generation,
			r.Recorder, edb,
			fmt.Sprintf("spec.classifier[\"namespace\"] %q must match metadata.namespace %q", ns, edb.Namespace))
	}

	// Build the flat-map request, resolving any Secret references.
	aggReq, err := r.buildRequest(ctx, edb)
	if err != nil {
		log.Error(err, "failed to build registration request")
		markTransientFailure(&edb.Status.Phase, &edb.Status.Conditions, edb.Generation,
			EventReasonSecretError, err.Error())
		r.Recorder.Eventf(edb, corev1.EventTypeWarning, EventReasonSecretError,
			"failed to read credentials Secret: %s", err)
		return ctrl.Result{}, err // requeue with backoff
	}

	// The namespace used in the aggregator URL comes from the classifier; fall
	// back to the CR's own namespace if the classifier does not contain one.
	namespace := resolveAggregatorNamespace(edb)

	// Call the aggregator.
	if err := r.Aggregator.RegisterExternalDatabase(ctx, namespace, aggReq); err != nil {
		log.Error(err, "failed to register external database in dbaas-aggregator")
		return handleAggregatorError(&edb.Status.Phase, &edb.Status.Conditions, edb.Generation, r.Recorder, edb, err)
	}

	log.Info("external database registered successfully",
		"type", edb.Spec.Type, "dbName", edb.Spec.DbName)
	markSucceeded(&edb.Status.Phase, &edb.Status.Conditions, edb.Generation, EventReasonDatabaseRegistered)
	r.Recorder.Eventf(edb, corev1.EventTypeNormal, EventReasonDatabaseRegistered,
		"registered with dbaas-aggregator (type=%s, dbName=%s)", edb.Spec.Type, edb.Spec.DbName)
	return ctrl.Result{}, nil
}

// buildRequest assembles an ExternalDatabaseRequest from the CR spec.
// For each ConnectionProperty that has a credentialsSecretRef it reads the
// referenced Secret and injects the mapped keys into the flat map.
func (r *ExternalDatabaseDeclarationReconciler) buildRequest(
	ctx context.Context,
	edb *dbaasv1alpha1.ExternalDatabaseDeclaration,
) (*aggregatorclient.ExternalDatabaseRequest, error) {
	connProps, err := r.buildConnectionProperties(ctx, edb)
	if err != nil {
		return nil, err
	}

	return &aggregatorclient.ExternalDatabaseRequest{
		Classifier:                 edb.Spec.Classifier,
		Type:                       edb.Spec.Type,
		DbName:                     edb.Spec.DbName,
		ConnectionProperties:       connProps,
		UpdateConnectionProperties: true,
	}, nil
}

func resolveAggregatorNamespace(edb *dbaasv1alpha1.ExternalDatabaseDeclaration) string {
	if namespace := edb.Spec.Classifier["namespace"]; namespace != "" {
		return namespace
	}
	return edb.Namespace
}

func (r *ExternalDatabaseDeclarationReconciler) buildConnectionProperties(
	ctx context.Context,
	edb *dbaasv1alpha1.ExternalDatabaseDeclaration,
) ([]map[string]string, error) {
	connProps := make([]map[string]string, 0, len(edb.Spec.ConnectionProperties))

	for i, cp := range edb.Spec.ConnectionProperties {
		flat := make(map[string]string, len(cp.ExtraProperties)+3)

		// Extra properties are merged first so that typed fields and resolved
		// Secret credentials always win on key collisions.
		for k, v := range cp.ExtraProperties {
			flat[k] = v
		}

		flat["role"] = cp.Role

		if err := r.applySecretCredentials(ctx, edb.Namespace, i, cp, flat); err != nil {
			return nil, err
		}

		connProps = append(connProps, flat)
	}

	return connProps, nil
}

func (r *ExternalDatabaseDeclarationReconciler) applySecretCredentials(
	ctx context.Context,
	namespace string,
	index int,
	cp dbaasv1alpha1.ConnectionProperty,
	flat map[string]string,
) error {
	if cp.CredentialsSecretRef == nil {
		return nil
	}

	ref := cp.CredentialsSecretRef
	secret := &corev1.Secret{}
	if err := r.Get(ctx, types.NamespacedName{Namespace: namespace, Name: ref.Name}, secret); err != nil {
		return fmt.Errorf(
			"connectionProperties[%d]: get Secret %q: %w",
			index, ref.Name, err)
	}

	// Defence-in-depth duplicate name check — CRD CEL validation should catch this
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
			return fmt.Errorf(
				"connectionProperties[%d]: Secret %q missing key %q",
				index, ref.Name, km.Key)
		}
		if len(val) == 0 {
			return fmt.Errorf(
				"connectionProperties[%d]: Secret %q key %q is empty",
				index, ref.Name, km.Key)
		}
		flat[km.Name] = string(val)
	}
	return nil
}

// SetupWithManager sets up the controller with the Manager.
// GenerationChangedPredicate ensures the controller reconciles only when the
// spec changes (metadata.generation increments), not on its own status updates.
//
// opts allows callers to customise the controller's behaviour — most notably
// the RateLimiter, which controls the exponential backoff applied when
// Reconcile returns an error (BackingOff phase).  Pass
// ctrlcontroller.Options{} to keep the controller-runtime defaults.
func (r *ExternalDatabaseDeclarationReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1alpha1.ExternalDatabaseDeclaration{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		WithOptions(opts).
		Named("externaldatabasedeclaration").
		Complete(r)
}
