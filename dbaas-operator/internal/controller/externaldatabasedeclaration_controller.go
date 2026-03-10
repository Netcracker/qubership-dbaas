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
	"fmt"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/predicate"

	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

const conditionTypeRegistered = "Registered"

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
		if patchErr := r.Status().Patch(ctx, edb, client.MergeFrom(original)); patchErr != nil {
			log.Error(patchErr, "patch ExternalDatabaseDeclaration status")
			retErr = errors.Join(retErr, patchErr)
		}
	}()

	// Mark as Updating while we work; clear stale conditions.
	edb.Status.Phase = dbaasv1alpha1.PhaseUpdating
	edb.Status.Conditions = nil

	// Pre-flight validation: every connectionProperty must have a non-empty role.
	// The CRD already enforces this via MinLength=1, but we check defensively so
	// the controller never sends an invalid request to the aggregator.
	for i, cp := range edb.Spec.ConnectionProperties {
		if cp.Role == "" {
			msg := fmt.Sprintf("connectionProperties[%d]: missing required field 'role'", i)
			log.Info("invalid spec", "reason", msg)
			edb.Status.Phase = dbaasv1alpha1.PhaseInvalidConfiguration
			setCondition(&edb.Status.Conditions, edb.Generation,
				conditionTypeRegistered, metav1.ConditionFalse, EventReasonInvalidSpec, msg)
			r.Recorder.Event(edb, corev1.EventTypeWarning, EventReasonInvalidSpec, msg)
			return ctrl.Result{}, nil
		}
	}

	// Build the flat-map request, resolving any Secret references.
	aggReq, err := r.buildRequest(ctx, edb)
	if err != nil {
		log.Error(err, "failed to build registration request")
		edb.Status.Phase = dbaasv1alpha1.PhaseBackingOff
		setCondition(&edb.Status.Conditions, edb.Generation,
			conditionTypeRegistered, metav1.ConditionFalse, EventReasonSecretError, err.Error())
		r.Recorder.Eventf(edb, corev1.EventTypeWarning, EventReasonSecretError,
			"failed to read credentials Secret: %s", err)
		return ctrl.Result{}, err // requeue with backoff
	}

	// The namespace used in the aggregator URL comes from the classifier; fall
	// back to the CR's own namespace if the classifier does not contain one.
	namespace := edb.Spec.Classifier["namespace"]
	if namespace == "" {
		namespace = edb.Namespace
	}

	// Call the aggregator.
	if err := r.Aggregator.RegisterExternalDatabase(ctx, namespace, aggReq); err != nil {
		log.Error(err, "failed to register external database in dbaas-aggregator")

		var aggErr *aggregatorclient.AggregatorError
		if errors.As(err, &aggErr) {
			switch {
			case aggErr.IsAuthError():
				// 401 — operator credentials or role binding misconfigured.
				// This is NOT a spec error: the user should not edit the CR.
				// Retry so the operator recovers once the admin fixes the credentials.
				edb.Status.Phase = dbaasv1alpha1.PhaseBackingOff
				setCondition(&edb.Status.Conditions, edb.Generation,
					conditionTypeRegistered, metav1.ConditionFalse, EventReasonUnauthorized, aggErr.Error())
				r.Recorder.Eventf(edb, corev1.EventTypeWarning, EventReasonUnauthorized,
					"dbaas-aggregator rejected operator credentials (HTTP 401) — check Secret and DB_CLIENT role")
				return ctrl.Result{}, err // requeue with backoff
			case aggErr.IsClientError():
				// 400, 403, 409 — spec error; retrying won't help until the spec changes.
				edb.Status.Phase = dbaasv1alpha1.PhaseInvalidConfiguration
				setCondition(&edb.Status.Conditions, edb.Generation,
					conditionTypeRegistered, metav1.ConditionFalse, EventReasonAggregatorRejected, aggErr.Error())
				r.Recorder.Eventf(edb, corev1.EventTypeWarning, EventReasonAggregatorRejected,
					"dbaas-aggregator rejected request: %s", aggErr)
				return ctrl.Result{}, nil // do not requeue
			}
		}

		// 5xx / network error — transient; requeue with backoff.
		edb.Status.Phase = dbaasv1alpha1.PhaseBackingOff
		setCondition(&edb.Status.Conditions, edb.Generation,
			conditionTypeRegistered, metav1.ConditionFalse, EventReasonAggregatorError, err.Error())
		r.Recorder.Eventf(edb, corev1.EventTypeWarning, EventReasonAggregatorError,
			"dbaas-aggregator error: %s", err)
		return ctrl.Result{}, err
	}

	log.Info("external database registered successfully",
		"type", edb.Spec.Type, "dbName", edb.Spec.DbName)
	edb.Status.Phase = dbaasv1alpha1.PhaseUpdated
	setCondition(&edb.Status.Conditions, edb.Generation,
		conditionTypeRegistered, metav1.ConditionTrue, EventReasonRegistered, "")
	r.Recorder.Eventf(edb, corev1.EventTypeNormal, EventReasonRegistered,
		"registered with dbaas-aggregator (type=%s, dbName=%s)", edb.Spec.Type, edb.Spec.DbName)
	return ctrl.Result{}, nil
}

// buildRequest assembles an ExternalDatabaseRequest from the CR spec.
// For each ConnectionProperty that has a credentialsSecretRef it reads the
// referenced Secret and injects the username/password into the flat map.
func (r *ExternalDatabaseDeclarationReconciler) buildRequest(
	ctx context.Context,
	edb *dbaasv1alpha1.ExternalDatabaseDeclaration,
) (*aggregatorclient.ExternalDatabaseRequest, error) {
	connProps := make([]map[string]string, 0, len(edb.Spec.ConnectionProperties))

	for i, cp := range edb.Spec.ConnectionProperties {
		flat := make(map[string]string)

		// Typed fields.
		flat["role"] = cp.Role
		if cp.URL != "" {
			flat["url"] = cp.URL
		}
		if cp.Host != "" {
			flat["host"] = cp.Host
		}
		if cp.Port != "" {
			flat["port"] = cp.Port
		}
		if cp.AuthDbName != "" {
			flat["authDbName"] = cp.AuthDbName
		}

		// Adapter-specific extra properties (merged last so they cannot
		// accidentally shadow the typed fields above).
		for k, v := range cp.ExtraProperties {
			flat[k] = v
		}

		// Credentials from a Kubernetes Secret.
		if cp.CredentialsSecretRef != nil {
			secret := &corev1.Secret{}
			key := types.NamespacedName{
				Namespace: edb.Namespace,
				Name:      cp.CredentialsSecretRef.Name,
			}
			if err := r.Get(ctx, key, secret); err != nil {
				return nil, fmt.Errorf(
					"connectionProperties[%d]: get Secret %q: %w",
					i, cp.CredentialsSecretRef.Name, err)
			}

			usernameKey := cp.CredentialsSecretRef.UsernameKey
			if usernameKey == "" {
				usernameKey = "username"
			}
			passwordKey := cp.CredentialsSecretRef.PasswordKey
			if passwordKey == "" {
				passwordKey = "password"
			}

			flat["username"] = string(secret.Data[usernameKey])
			flat["password"] = string(secret.Data[passwordKey])
		}

		connProps = append(connProps, flat)
	}

	return &aggregatorclient.ExternalDatabaseRequest{
		Classifier:                 edb.Spec.Classifier,
		Type:                       edb.Spec.Type,
		DbName:                     edb.Spec.DbName,
		ConnectionProperties:       connProps,
		UpdateConnectionProperties: edb.Spec.UpdateConnectionProperties,
	}, nil
}

// SetupWithManager sets up the controller with the Manager.
// GenerationChangedPredicate ensures the controller reconciles only when the
// spec changes (metadata.generation increments), not on its own status updates.
func (r *ExternalDatabaseDeclarationReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1alpha1.ExternalDatabaseDeclaration{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Named("externaldatabasedeclaration").
		Complete(r)
}
