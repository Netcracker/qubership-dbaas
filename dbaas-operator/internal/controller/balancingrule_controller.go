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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=microservicebalancingrules,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=microservicebalancingrules/finalizers,verbs=update
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=microservicebalancingrules/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebalancingrules,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebalancingrules/finalizers,verbs=update
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=namespacebalancingrules/status,verbs=get;update;patch
// permanentbalancingrules is operator-namespace-only (informer scoped to CLOUD_NAMESPACE).
// The namespace= field makes controller-gen emit a namespaced Role (kustomize substitutes the
// namespace) bound by the RoleBinding in config/rbac/role_binding.yaml — matching the
// production helm Role, not a ClusterRole.
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=permanentbalancingrules,verbs=get;list;watch;patch,namespace=system
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=permanentbalancingrules/finalizers,verbs=update,namespace=system
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=permanentbalancingrules/status,verbs=get;update;patch,namespace=system

import (
	"context"
	"fmt"
	"reflect"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
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
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

// BalancingRuleReconciler reconciles the three balancing-rule CRDs into the
// existing dbaas-aggregator balancing rule administration APIs.
type BalancingRuleReconciler struct {
	client.Client
	Scheme      *runtime.Scheme
	Aggregator  *aggregatorclient.AggregatorClient
	Recorder    record.EventRecorder
	Ownership   *ownership.OwnershipResolver
	MyNamespace string

	bindingTriggerTracker
}

func generationOrLifecycleChangedPredicate() predicate.Funcs {
	return predicate.Funcs{
		CreateFunc: func(event.CreateEvent) bool {
			return true
		},
		DeleteFunc: func(event.DeleteEvent) bool {
			return true
		},
		UpdateFunc: func(e event.UpdateEvent) bool {
			if e.ObjectOld == nil || e.ObjectNew == nil {
				return true
			}
			if e.ObjectOld.GetGeneration() != e.ObjectNew.GetGeneration() {
				return true
			}
			if !e.ObjectOld.GetDeletionTimestamp().Equal(e.ObjectNew.GetDeletionTimestamp()) {
				return true
			}
			return !reflect.DeepEqual(e.ObjectOld.GetFinalizers(), e.ObjectNew.GetFinalizers())
		},
		GenericFunc: func(event.GenericEvent) bool {
			return true
		},
	}
}

func (r *BalancingRuleReconciler) ReconcileMicroservice(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	rule := &dbaasv1.MicroserviceBalancingRule{}
	if err := r.Get(ctx, req.NamespacedName, rule); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(microserviceRuleTriggerKey(req.Namespace, req.Name))
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := microserviceRuleTriggerKey(rule.Namespace, rule.Name)
	trigger := r.triggerForKey(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, rule.Namespace, rule.Name, "MicroserviceBalancingRule")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(key)
		return result, nil
	}
	recordReconcileTrigger(controllerMBR, trigger)

	if !rule.DeletionTimestamp.IsZero() {
		return ctrl.Result{}, r.reconcileMicroserviceDelete(ctx, rule, requestID)
	}
	if !controllerutil.ContainsFinalizer(rule, dbaasv1.MicroserviceBalancingRuleFinalizer) {
		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.AddFinalizer(rule, dbaasv1.MicroserviceBalancingRuleFinalizer)
		return ctrl.Result{}, r.Patch(ctx, rule, patch)
	}

	original := rule.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), rule, original, &retErr,
			func(rule *dbaasv1.MicroserviceBalancingRule, _ error) bool {
				return isTerminal(rule.Status.Conditions, rule.Generation)
			},
			"MicroserviceBalancingRule")
	}()

	rule.Status.Phase = dbaasv1.PhaseProcessing
	rule.Status.LastRequestID = requestID

	if msg := r.validateMicroserviceRule(rule); msg != "" {
		return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
	}

	if err := r.cleanupSupersededMicroserviceRules(ctx, rule); err != nil {
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	aggStart := time.Now()
	err = r.Aggregator.ApplyMicroserviceBalancingRules(ctx, rule.Namespace, microserviceRequestsFromSpec(rule.Spec.Rules))
	recordAggregatorCall(controllerBR, operationApplyMicroserviceRule, aggStart, err)
	if err != nil {
		log.ErrorC(ctx, "failed to apply MicroserviceBalancingRule to dbaas-aggregator: %v", err)
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	markSucceeded(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, EventReasonBalancingRuleApplied)
	rule.Status.AppliedRules = appliedMicroserviceRulesFromSpec(rule.Spec.Rules)
	log.InfoC(ctx, "MicroserviceBalancingRule applied to dbaas-aggregator namespace=%s name=%s rules=%d requestId=%s",
		rule.Namespace, rule.Name, len(rule.Spec.Rules), requestID)
	r.Recorder.Eventf(rule, corev1.EventTypeNormal, EventReasonBalancingRuleApplied,
		"microservice balancing rules applied to dbaas-aggregator (rules=%d, requestId=%s)",
		len(rule.Spec.Rules), requestID)
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) ReconcileNamespace(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	rule := &dbaasv1.NamespaceBalancingRule{}
	if err := r.Get(ctx, req.NamespacedName, rule); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(namespaceRuleTriggerKey(req.Namespace, req.Name))
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := namespaceRuleTriggerKey(rule.Namespace, rule.Name)
	trigger := r.triggerForKey(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, rule.Namespace, rule.Name, "NamespaceBalancingRule")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(key)
		return result, nil
	}
	recordReconcileTrigger(controllerNBR, trigger)

	if !rule.DeletionTimestamp.IsZero() {
		return ctrl.Result{}, r.reconcileNamespaceDelete(ctx, rule, requestID)
	}
	if !controllerutil.ContainsFinalizer(rule, dbaasv1.NamespaceBalancingRuleFinalizer) {
		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.AddFinalizer(rule, dbaasv1.NamespaceBalancingRuleFinalizer)
		return ctrl.Result{}, r.Patch(ctx, rule, patch)
	}

	original := rule.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), rule, original, &retErr,
			func(rule *dbaasv1.NamespaceBalancingRule, _ error) bool {
				return isTerminal(rule.Status.Conditions, rule.Generation)
			},
			"NamespaceBalancingRule")
	}()

	rule.Status.Phase = dbaasv1.PhaseProcessing
	rule.Status.LastRequestID = requestID

	if msg, err := r.validateNamespaceRule(ctx, rule); err != nil {
		return ctrl.Result{}, err
	} else if msg != "" {
		return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
	}

	// Delete rules no longer present in spec. cleanupSupersededNamespaceRules
	// prunes each successfully-deleted entry from status.AppliedRules, so a
	// mid-list delete failure leaves the still-live rules recorded — preventing
	// orphans on a later reconcile/delete that iterates status.AppliedRules.
	if err := r.cleanupSupersededNamespaceRules(ctx, rule); err != nil {
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	// Seed the applied set from the (now-pruned) status, preserving insertion
	// order. A failure partway through apply must not drop rules that are still
	// live aggregator-side: an entry is upserted only after a successful apply,
	// so status.AppliedRules always reflects what the aggregator currently holds.
	applied := make(map[string]dbaasv1.NamespaceBalancingRuleAppliedRule, len(rule.Status.AppliedRules))
	appliedOrder := make([]string, 0, len(rule.Status.AppliedRules))
	for _, a := range rule.Status.AppliedRules {
		if a.Name == "" {
			continue
		}
		if _, ok := applied[a.Name]; !ok {
			appliedOrder = append(appliedOrder, a.Name)
		}
		applied[a.Name] = a
	}
	syncAppliedStatus := func() {
		out := make([]dbaasv1.NamespaceBalancingRuleAppliedRule, 0, len(appliedOrder))
		for _, name := range appliedOrder {
			if a, ok := applied[name]; ok {
				out = append(out, a)
			}
		}
		rule.Status.AppliedRules = out
	}

	// Apply desired rules; upsert into the applied set only on a successful apply.
	for _, item := range rule.Spec.Rules {
		aggStart := time.Now()
		err = r.Aggregator.ApplyNamespaceBalancingRule(ctx, rule.Namespace, item.Name, namespaceRequestFromSpecItem(item))
		recordAggregatorCall(controllerBR, operationApplyNamespaceRule, aggStart, err)
		if err != nil {
			syncAppliedStatus()
			log.ErrorC(ctx, "failed to apply NamespaceBalancingRule to dbaas-aggregator: %v", err)
			return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
		}
		if _, ok := applied[item.Name]; !ok {
			appliedOrder = append(appliedOrder, item.Name)
		}
		applied[item.Name] = dbaasv1.NamespaceBalancingRuleAppliedRule(item)
		syncAppliedStatus()
	}

	markSucceeded(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, EventReasonBalancingRuleApplied)
	syncAppliedStatus()
	log.InfoC(ctx, "NamespaceBalancingRule applied to dbaas-aggregator namespace=%s name=%s rules=%d requestId=%s",
		rule.Namespace, rule.Name, len(rule.Spec.Rules), requestID)
	r.Recorder.Eventf(rule, corev1.EventTypeNormal, EventReasonBalancingRuleApplied,
		"namespace balancing rules applied to dbaas-aggregator (rules=%d, requestId=%s)",
		len(rule.Spec.Rules), requestID)
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) ReconcilePermanent(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	rule := &dbaasv1.PermanentBalancingRule{}
	if err := r.Get(ctx, req.NamespacedName, rule); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	// PermanentBalancingRule is an operator-namespace-only resource and is
	// intentionally decoupled from NamespaceBinding. The manager's informer is
	// scoped to CLOUD_NAMESPACE, so only CRs in the operator namespace reach this
	// reconcile; validatePermanentRule re-checks metadata.namespace defensively.
	// Neither the CR's own namespace nor its target namespaces require a binding.
	recordReconcileTrigger(controllerPBR, triggerSpecChange)

	if !rule.DeletionTimestamp.IsZero() {
		return ctrl.Result{}, r.reconcilePermanentDelete(ctx, rule, requestID)
	}
	if !controllerutil.ContainsFinalizer(rule, dbaasv1.PermanentBalancingRuleFinalizer) {
		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.AddFinalizer(rule, dbaasv1.PermanentBalancingRuleFinalizer)
		return ctrl.Result{}, r.Patch(ctx, rule, patch)
	}

	original := rule.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), rule, original, &retErr,
			func(rule *dbaasv1.PermanentBalancingRule, _ error) bool {
				return isTerminal(rule.Status.Conditions, rule.Generation)
			},
			"PermanentBalancingRule")
	}()

	rule.Status.Phase = dbaasv1.PhaseProcessing
	rule.Status.LastRequestID = requestID

	if msg := r.validatePermanentRule(rule); msg != "" {
		return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
	}

	if err := r.cleanupSupersededPermanentRules(ctx, rule); err != nil {
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	aggStart := time.Now()
	err := r.Aggregator.ApplyPermanentBalancingRules(ctx, permanentRequestsFromSpec(rule.Spec.Rules))
	recordAggregatorCall(controllerBR, operationApplyPermanentRule, aggStart, err)
	if err != nil {
		log.ErrorC(ctx, "failed to apply PermanentBalancingRule to dbaas-aggregator: %v", err)
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	markSucceeded(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, EventReasonBalancingRuleApplied)
	rule.Status.AppliedRules = appliedPermanentRulesFromSpec(rule.Spec.Rules)
	log.InfoC(ctx, "PermanentBalancingRule applied to dbaas-aggregator namespace=%s name=%s rules=%d requestId=%s",
		rule.Namespace, rule.Name, len(rule.Spec.Rules), requestID)
	r.Recorder.Eventf(rule, corev1.EventTypeNormal, EventReasonBalancingRuleApplied,
		"permanent balancing rules applied to dbaas-aggregator (rules=%d, requestId=%s)",
		len(rule.Spec.Rules), requestID)
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	if err := ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.MicroserviceBalancingRule{},
			builder.WithPredicates(generationOrLifecycleChangedPredicate())).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueMicroserviceRulesForBinding)).
		WithOptions(opts).
		Named("microservicebalancingrule").
		Complete(reconcile.Func(r.ReconcileMicroservice)); err != nil {
		return err
	}

	if err := ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.NamespaceBalancingRule{},
			builder.WithPredicates(generationOrLifecycleChangedPredicate())).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueNamespaceRulesForBinding)).
		WithOptions(opts).
		Named("namespacebalancingrule").
		Complete(reconcile.Func(r.ReconcileNamespace)); err != nil {
		return err
	}

	// PermanentBalancingRule is operator-namespace-only and decoupled from
	// NamespaceBinding, so it has no binding watch. Its informer is scoped to
	// CLOUD_NAMESPACE by the manager's cache options.
	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.PermanentBalancingRule{},
			builder.WithPredicates(generationOrLifecycleChangedPredicate())).
		WithOptions(opts).
		Named("permanentbalancingrule").
		Complete(reconcile.Func(r.ReconcilePermanent))
}

func (r *BalancingRuleReconciler) reconcileMicroserviceDelete(
	ctx context.Context,
	rule *dbaasv1.MicroserviceBalancingRule,
	requestID string,
) error {
	if controllerutil.ContainsFinalizer(rule, dbaasv1.MicroserviceBalancingRuleFinalizer) {
		for _, applied := range rule.Status.AppliedRules {
			if applied.Type == "" || len(applied.Microservices) == 0 {
				continue
			}
			if err := r.cleanupMicroserviceTargets(ctx, rule.Namespace, applied.Type, applied.Microservices); err != nil {
				r.Recorder.Eventf(rule, corev1.EventTypeWarning, EventReasonAggregatorError,
					"failed to clean up microservice balancing rule during deletion: %s (requestId=%s)",
					err, requestID)
				return err
			}
		}

		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.RemoveFinalizer(rule, dbaasv1.MicroserviceBalancingRuleFinalizer)
		if err := r.Patch(ctx, rule, patch); err != nil {
			return err
		}
	}
	return nil
}

func (r *BalancingRuleReconciler) reconcileNamespaceDelete(
	ctx context.Context,
	rule *dbaasv1.NamespaceBalancingRule,
	requestID string,
) error {
	if controllerutil.ContainsFinalizer(rule, dbaasv1.NamespaceBalancingRuleFinalizer) {
		for _, applied := range rule.Status.AppliedRules {
			if applied.Name == "" {
				continue
			}
			if err := r.deleteNamespaceRule(ctx, rule.Namespace, applied.Name); err != nil {
				r.Recorder.Eventf(rule, corev1.EventTypeWarning, EventReasonAggregatorError,
					"failed to delete namespace balancing rule during deletion: %s (requestId=%s)",
					err, requestID)
				return err
			}
		}

		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.RemoveFinalizer(rule, dbaasv1.NamespaceBalancingRuleFinalizer)
		if err := r.Patch(ctx, rule, patch); err != nil {
			return err
		}
	}
	return nil
}

func (r *BalancingRuleReconciler) reconcilePermanentDelete(
	ctx context.Context,
	rule *dbaasv1.PermanentBalancingRule,
	requestID string,
) error {
	if controllerutil.ContainsFinalizer(rule, dbaasv1.PermanentBalancingRuleFinalizer) {
		for _, applied := range rule.Status.AppliedRules {
			if applied.DBType == "" || len(applied.Namespaces) == 0 {
				continue
			}
			if err := r.deletePermanentTargets(ctx, applied.DBType, applied.Namespaces); err != nil {
				r.Recorder.Eventf(rule, corev1.EventTypeWarning, EventReasonAggregatorError,
					"failed to delete permanent balancing rule during deletion: %s (requestId=%s)",
					err, requestID)
				return err
			}
		}

		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.RemoveFinalizer(rule, dbaasv1.PermanentBalancingRuleFinalizer)
		if err := r.Patch(ctx, rule, patch); err != nil {
			return err
		}
	}
	return nil
}

func (r *BalancingRuleReconciler) cleanupSupersededMicroserviceRules(
	ctx context.Context,
	rule *dbaasv1.MicroserviceBalancingRule,
) error {
	desired := desiredMicroserviceByType(rule.Spec.Rules)
	for _, applied := range rule.Status.AppliedRules {
		if applied.Type == "" || len(applied.Microservices) == 0 {
			continue
		}
		removed := differenceStrings(applied.Microservices, desired[strings.ToLower(applied.Type)])
		if len(removed) == 0 {
			continue
		}
		if err := r.cleanupMicroserviceTargets(ctx, rule.Namespace, applied.Type, removed); err != nil {
			return err
		}
	}
	return nil
}

// cleanupSupersededNamespaceRules deletes rules recorded in status but no longer
// present in spec. Each successfully-deleted entry is pruned from
// status.AppliedRules immediately; on the first failed delete it returns,
// leaving the not-yet-deleted (still live aggregator-side) rules recorded so a
// subsequent reconcile retries them rather than orphaning them.
func (r *BalancingRuleReconciler) cleanupSupersededNamespaceRules(
	ctx context.Context,
	rule *dbaasv1.NamespaceBalancingRule,
) error {
	removed := removedNamespaceAppliedRules(rule.Status.AppliedRules, rule.Spec.Rules)
	if len(removed) == 0 {
		return nil
	}
	deleted := make(map[string]struct{}, len(removed))
	for _, item := range removed {
		if item.Name == "" {
			continue
		}
		if err := r.deleteNamespaceRule(ctx, rule.Namespace, item.Name); err != nil {
			rule.Status.AppliedRules = retainNamespaceAppliedExcept(rule.Status.AppliedRules, deleted)
			return err
		}
		deleted[item.Name] = struct{}{}
	}
	rule.Status.AppliedRules = retainNamespaceAppliedExcept(rule.Status.AppliedRules, deleted)
	return nil
}

// retainNamespaceAppliedExcept returns the applied rules with every entry whose
// name is in deleted removed, preserving order. The input slice is not mutated.
func retainNamespaceAppliedExcept(
	applied []dbaasv1.NamespaceBalancingRuleAppliedRule,
	deleted map[string]struct{},
) []dbaasv1.NamespaceBalancingRuleAppliedRule {
	if len(deleted) == 0 {
		return applied
	}
	out := make([]dbaasv1.NamespaceBalancingRuleAppliedRule, 0, len(applied))
	for _, a := range applied {
		if _, gone := deleted[a.Name]; gone {
			continue
		}
		out = append(out, a)
	}
	return out
}

func (r *BalancingRuleReconciler) cleanupSupersededPermanentRules(
	ctx context.Context,
	rule *dbaasv1.PermanentBalancingRule,
) error {
	desired := desiredPermanentByDbType(rule.Spec.Rules)
	for _, applied := range rule.Status.AppliedRules {
		if applied.DBType == "" || len(applied.Namespaces) == 0 {
			continue
		}
		removed := differenceStrings(applied.Namespaces, desired[strings.ToLower(applied.DBType)])
		if len(removed) == 0 {
			continue
		}
		if err := r.deletePermanentTargets(ctx, applied.DBType, removed); err != nil {
			return err
		}
	}
	return nil
}

func (r *BalancingRuleReconciler) cleanupMicroserviceTargets(
	ctx context.Context,
	namespace, dbType string,
	microservices []string,
) error {
	reqBody := []aggregatorclient.OnMicroserviceRuleRequest{{
		Type:          dbType,
		Rules:         []aggregatorclient.RuleOnMicroservice{},
		Microservices: microservices,
	}}
	aggStart := time.Now()
	err := r.Aggregator.ApplyMicroserviceBalancingRules(ctx, namespace, reqBody)
	recordAggregatorCall(controllerBR, operationCleanupMicroserviceRule, aggStart, err)
	return err
}

func (r *BalancingRuleReconciler) deleteNamespaceRule(
	ctx context.Context,
	namespace, ruleName string,
) error {
	aggStart := time.Now()
	err := r.Aggregator.DeleteNamespaceBalancingRule(ctx, namespace, ruleName)
	recordAggregatorCall(controllerBR, operationDeleteNamespaceRule, aggStart, err)
	return err
}

func (r *BalancingRuleReconciler) deletePermanentTargets(
	ctx context.Context,
	dbType string,
	namespaces []string,
) error {
	reqBody := []aggregatorclient.PermanentBalancingRuleDeleteRequest{{
		DBType:     dbType,
		Namespaces: namespaces,
	}}
	aggStart := time.Now()
	err := r.Aggregator.DeletePermanentBalancingRules(ctx, reqBody)
	recordAggregatorCall(controllerBR, operationDeletePermanentRule, aggStart, err)
	return err
}

// msgSpecRulesEmpty is the validation message returned when spec.rules is empty.
const msgSpecRulesEmpty = "spec.rules must not be empty"

func (r *BalancingRuleReconciler) validateMicroserviceRule(rule *dbaasv1.MicroserviceBalancingRule) string {
	if rule.Name != dbaasv1.MicroserviceBalancingRuleName {
		return fmt.Sprintf("metadata.name must be %q", dbaasv1.MicroserviceBalancingRuleName)
	}
	if len(rule.Spec.Rules) == 0 {
		return msgSpecRulesEmpty
	}
	seen := map[string]struct{}{}
	for i, item := range rule.Spec.Rules {
		if strings.TrimSpace(item.Type) == "" {
			return fmt.Sprintf("spec.rules[%d].type must not be blank", i)
		}
		if strings.TrimSpace(item.Label) == "" {
			return fmt.Sprintf("spec.rules[%d].label must not be blank", i)
		}
		if !strings.Contains(item.Label, "=") || strings.Count(item.Label, "=") != 1 {
			return fmt.Sprintf("spec.rules[%d].label must be in key=value format", i)
		}
		for j, microservice := range item.Microservices {
			if strings.TrimSpace(microservice) == "" {
				return fmt.Sprintf("spec.rules[%d].microservices[%d] must not be blank", i, j)
			}
			key := strings.ToLower(item.Type) + "\x00" + microservice
			if _, ok := seen[key]; ok {
				return fmt.Sprintf("spec.rules contains duplicate microservice %q for type %q", microservice, item.Type)
			}
			seen[key] = struct{}{}
		}
	}
	return ""
}

func (r *BalancingRuleReconciler) validateNamespaceRule(ctx context.Context, rule *dbaasv1.NamespaceBalancingRule) (string, error) {
	if rule.Name != dbaasv1.NamespaceBalancingRuleName {
		return fmt.Sprintf("metadata.name must be %q", dbaasv1.NamespaceBalancingRuleName), nil
	}
	if len(rule.Spec.Rules) == 0 {
		return msgSpecRulesEmpty, nil
	}
	names := map[string]struct{}{}
	typeOrders := map[string]map[int64]struct{}{}
	for i, item := range rule.Spec.Rules {
		if strings.TrimSpace(item.Name) == "" {
			return fmt.Sprintf("spec.rules[%d].name must not be blank", i), nil
		}
		nameKey := strings.ToLower(item.Name)
		if _, ok := names[nameKey]; ok {
			return fmt.Sprintf("spec.rules contains duplicate name %q", item.Name), nil
		}
		names[nameKey] = struct{}{}
		if strings.TrimSpace(item.Type) == "" {
			return fmt.Sprintf("spec.rules[%d].type must not be blank", i), nil
		}
		if strings.TrimSpace(item.PhysicalDatabaseID) == "" {
			return fmt.Sprintf("spec.rules[%d].physicalDatabaseId must not be blank", i), nil
		}
		typeKey := strings.ToLower(item.Type)
		if typeOrders[typeKey] == nil {
			typeOrders[typeKey] = map[int64]struct{}{}
		}
		if _, ok := typeOrders[typeKey][item.Order]; ok {
			return fmt.Sprintf("spec.rules contains duplicate order %d for type %q", item.Order, item.Type), nil
		}
		typeOrders[typeKey][item.Order] = struct{}{}
	}
	if r.Client != nil {
		if msg, err := r.validateNamespaceRuleGlobalConflicts(ctx, rule, names); msg != "" || err != nil {
			return msg, err
		}
	}
	return "", nil
}

func (r *BalancingRuleReconciler) validateNamespaceRuleGlobalConflicts(
	ctx context.Context,
	rule *dbaasv1.NamespaceBalancingRule,
	names map[string]struct{},
) (string, error) {
	list := &dbaasv1.NamespaceBalancingRuleList{}
	if err := r.List(ctx, list); err != nil {
		return "", err
	}

	// Best-effort pre-check only. Aggregator-side validation remains the
	// authority because another controller can race this List before either
	// rule is applied. We check names here because namespace rules are keyed by
	// name in the aggregator and duplicate names can silently clobber each
	// other; global (type, order) conflicts are enforced by the aggregator's
	// 409 response and mapped to InvalidConfiguration by handleAggregatorError.
	for i := range list.Items {
		other := &list.Items[i]
		if other.Namespace == rule.Namespace && other.Name == rule.Name {
			continue
		}
		for _, item := range other.Spec.Rules {
			if _, ok := names[strings.ToLower(item.Name)]; ok {
				return fmt.Sprintf("spec.rules contains name %q already managed by NamespaceBalancingRule %q/%q", item.Name, other.Namespace, other.Name), nil
			}
		}
	}
	return "", nil
}

func (r *BalancingRuleReconciler) validatePermanentRule(rule *dbaasv1.PermanentBalancingRule) string {
	if rule.Name != dbaasv1.PermanentBalancingRuleName {
		return fmt.Sprintf("metadata.name must be %q", dbaasv1.PermanentBalancingRuleName)
	}
	if r.MyNamespace != "" && rule.Namespace != r.MyNamespace {
		return fmt.Sprintf("metadata.namespace must be operator namespace %q", r.MyNamespace)
	}
	if len(rule.Spec.Rules) == 0 {
		return msgSpecRulesEmpty
	}
	seen := map[string]struct{}{}
	for i, item := range rule.Spec.Rules {
		if strings.TrimSpace(item.DBType) == "" {
			return fmt.Sprintf("spec.rules[%d].dbType must not be blank", i)
		}
		if strings.TrimSpace(item.PhysicalDatabaseID) == "" {
			return fmt.Sprintf("spec.rules[%d].physicalDatabaseId must not be blank", i)
		}
		for j, namespace := range item.Namespaces {
			if strings.TrimSpace(namespace) == "" {
				return fmt.Sprintf("spec.rules[%d].namespaces[%d] must not be blank", i, j)
			}
			key := strings.ToLower(item.DBType) + "\x00" + namespace
			if _, ok := seen[key]; ok {
				return fmt.Sprintf("spec.rules contains duplicate namespace %q for dbType %q", namespace, item.DBType)
			}
			seen[key] = struct{}{}
		}
	}
	return ""
}

func (r *BalancingRuleReconciler) enqueueMicroserviceRulesForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	return enqueueForBindingList(ctx, r.Client, &dbaasv1.MicroserviceBalancingRuleList{}, obj.GetNamespace(),
		func(o client.Object) {
			r.stampBindingTrigger(microserviceRuleTriggerKey(o.GetNamespace(), o.GetName()))
		})
}

func (r *BalancingRuleReconciler) enqueueNamespaceRulesForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	return enqueueForBindingList(ctx, r.Client, &dbaasv1.NamespaceBalancingRuleList{}, obj.GetNamespace(),
		func(o client.Object) { r.stampBindingTrigger(namespaceRuleTriggerKey(o.GetNamespace(), o.GetName())) })
}

func (r *BalancingRuleReconciler) triggerForKey(key string) string {
	if r.consumeBindingTrigger(key) {
		return triggerNamespaceBindingChange
	}
	return triggerSpecChange
}

func microserviceRuleTriggerKey(namespace, name string) string {
	return "microservice/" + namespace + "/" + name
}

func namespaceRuleTriggerKey(namespace, name string) string {
	return "namespace/" + namespace + "/" + name
}

func differenceStrings(previous, current []string) []string {
	if len(previous) == 0 {
		return nil
	}
	currentSet := make(map[string]struct{}, len(current))
	for _, value := range current {
		currentSet[value] = struct{}{}
	}
	var removed []string
	for _, value := range previous {
		if _, ok := currentSet[value]; !ok {
			removed = append(removed, value)
		}
	}
	return removed
}

func microserviceRequestsFromSpec(
	rules []dbaasv1.MicroserviceBalancingRuleItem,
) []aggregatorclient.OnMicroserviceRuleRequest {
	reqs := make([]aggregatorclient.OnMicroserviceRuleRequest, 0, len(rules))
	for _, item := range rules {
		reqs = append(reqs, aggregatorclient.OnMicroserviceRuleRequest{
			Type: item.Type,
			Rules: []aggregatorclient.RuleOnMicroservice{{
				Label: item.Label,
			}},
			Microservices: item.Microservices,
		})
	}
	return reqs
}

func namespaceRequestFromSpecItem(
	item dbaasv1.NamespaceBalancingRuleItem,
) *aggregatorclient.NamespaceBalancingRuleRequest {
	order := item.Order
	return &aggregatorclient.NamespaceBalancingRuleRequest{
		Order: &order,
		Type:  item.Type,
		Rule: aggregatorclient.NamespaceBalancingRuleBody{
			Type: "perNamespace",
			Config: map[string]any{
				"perNamespace": map[string]any{
					"phydbid": item.PhysicalDatabaseID,
				},
			},
		},
	}
}

func permanentRequestsFromSpec(
	rules []dbaasv1.PermanentBalancingRuleItem,
) []aggregatorclient.PermanentBalancingRuleRequest {
	reqs := make([]aggregatorclient.PermanentBalancingRuleRequest, 0, len(rules))
	for _, item := range rules {
		reqs = append(reqs, aggregatorclient.PermanentBalancingRuleRequest{
			DBType:             item.DBType,
			PhysicalDatabaseID: item.PhysicalDatabaseID,
			Namespaces:         item.Namespaces,
		})
	}
	return reqs
}

func appliedMicroserviceRulesFromSpec(
	rules []dbaasv1.MicroserviceBalancingRuleItem,
) []dbaasv1.MicroserviceBalancingRuleAppliedRule {
	applied := make([]dbaasv1.MicroserviceBalancingRuleAppliedRule, 0, len(rules))
	for _, rule := range rules {
		applied = append(applied, dbaasv1.MicroserviceBalancingRuleAppliedRule{
			Type:          rule.Type,
			Microservices: append([]string(nil), rule.Microservices...),
		})
	}
	return applied
}

func removedNamespaceAppliedRules(
	applied []dbaasv1.NamespaceBalancingRuleAppliedRule,
	desired []dbaasv1.NamespaceBalancingRuleItem,
) []dbaasv1.NamespaceBalancingRuleAppliedRule {
	if len(applied) == 0 {
		return nil
	}
	desiredNames := make(map[string]struct{}, len(desired))
	for _, rule := range desired {
		desiredNames[rule.Name] = struct{}{}
	}
	removed := make([]dbaasv1.NamespaceBalancingRuleAppliedRule, 0)
	for _, rule := range applied {
		if _, ok := desiredNames[rule.Name]; !ok {
			removed = append(removed, rule)
		}
	}
	return removed
}

func appliedPermanentRulesFromSpec(
	rules []dbaasv1.PermanentBalancingRuleItem,
) []dbaasv1.PermanentBalancingRuleAppliedRule {
	applied := make([]dbaasv1.PermanentBalancingRuleAppliedRule, 0, len(rules))
	for _, rule := range rules {
		applied = append(applied, dbaasv1.PermanentBalancingRuleAppliedRule{
			DBType:     rule.DBType,
			Namespaces: append([]string(nil), rule.Namespaces...),
		})
	}
	return applied
}

func desiredMicroserviceByType(rules []dbaasv1.MicroserviceBalancingRuleItem) map[string][]string {
	desired := make(map[string][]string, len(rules))
	for _, rule := range rules {
		key := strings.ToLower(rule.Type)
		desired[key] = append(desired[key], rule.Microservices...)
	}
	return desired
}

func desiredPermanentByDbType(rules []dbaasv1.PermanentBalancingRuleItem) map[string][]string {
	desired := make(map[string][]string, len(rules))
	for _, rule := range rules {
		key := strings.ToLower(rule.DBType)
		desired[key] = append(desired[key], rule.Namespaces...)
	}
	return desired
}
