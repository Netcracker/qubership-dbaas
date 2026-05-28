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

// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbmicroservicebalancingrules,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbmicroservicebalancingrules/finalizers,verbs=update
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbmicroservicebalancingrules/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbnamespacebalancingrules,verbs=get;list;watch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbnamespacebalancingrules/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpermanentbalancingrules,verbs=get;list;watch;patch
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpermanentbalancingrules/finalizers,verbs=update
// +kubebuilder:rbac:groups=dbaas.netcracker.com,resources=dbpermanentbalancingrules/status,verbs=get;update;patch

import (
	"context"
	"fmt"
	"reflect"
	"strings"
	"sync"
	"time"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
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

	bindingTriggerMu     sync.Mutex
	bindingTriggerStamps map[string]struct{}
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

	rule := &dbaasv1.DbMicroserviceBalancingRule{}
	if err := r.Get(ctx, req.NamespacedName, rule); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(microserviceRuleTriggerKey(req.Namespace, req.Name))
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := microserviceRuleTriggerKey(rule.Namespace, rule.Name)
	trigger := r.triggerForKey(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, rule.Namespace, rule.Name, "DbMicroserviceBalancingRule")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(key)
		return result, nil
	}
	recordReconcileTrigger(controllerBR, trigger)

	if !rule.DeletionTimestamp.IsZero() {
		return r.reconcileMicroserviceDelete(ctx, rule, requestID)
	}
	if !controllerutil.ContainsFinalizer(rule, dbaasv1.DbMicroserviceBalancingRuleFinalizer) {
		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.AddFinalizer(rule, dbaasv1.DbMicroserviceBalancingRuleFinalizer)
		return ctrl.Result{}, r.Patch(ctx, rule, patch)
	}

	original := rule.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), rule, original, &retErr,
			func(rule *dbaasv1.DbMicroserviceBalancingRule, _ error) bool {
				return rule.Status.Phase == dbaasv1.PhaseSucceeded ||
					rule.Status.Phase == dbaasv1.PhaseInvalidConfiguration
			},
			"DbMicroserviceBalancingRule")
	}()

	rule.Status.Phase = dbaasv1.PhaseProcessing
	rule.Status.LastRequestID = requestID

	if msg, err := r.validateMicroserviceRule(ctx, rule); err != nil {
		return ctrl.Result{}, err
	} else if msg != "" {
		return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
	}

	if err := r.cleanupSupersededMicroserviceRules(ctx, rule); err != nil {
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	aggStart := time.Now()
	err = r.Aggregator.ApplyMicroserviceBalancingRules(ctx, rule.Namespace, microserviceRequestsFromSpec(rule.Spec.Rules))
	recordAggregatorCall(controllerBR, operationApplyMicroserviceRule, aggStart, err)
	if err != nil {
		log.ErrorC(ctx, "failed to apply DbMicroserviceBalancingRule to dbaas-aggregator: %v", err)
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	markSucceeded(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, EventReasonBalancingRuleApplied)
	rule.Status.AppliedRules = appliedMicroserviceRulesFromSpec(rule.Spec.Rules)
	log.InfoC(ctx, "DbMicroserviceBalancingRule applied to dbaas-aggregator namespace=%s name=%s rules=%d requestId=%s",
		rule.Namespace, rule.Name, len(rule.Spec.Rules), requestID)
	r.Recorder.Eventf(rule, corev1.EventTypeNormal, EventReasonBalancingRuleApplied,
		"microservice balancing rules applied to dbaas-aggregator (rules=%d, requestId=%s)",
		len(rule.Spec.Rules), requestID)
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) ReconcileNamespace(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	rule := &dbaasv1.DbNamespaceBalancingRule{}
	if err := r.Get(ctx, req.NamespacedName, rule); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(namespaceRuleTriggerKey(req.Namespace, req.Name))
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := namespaceRuleTriggerKey(rule.Namespace, rule.Name)
	trigger := r.triggerForKey(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, rule.Namespace, rule.Name, "DbNamespaceBalancingRule")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(key)
		return result, nil
	}
	recordReconcileTrigger(controllerBR, trigger)

	original := rule.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), rule, original, &retErr,
			func(rule *dbaasv1.DbNamespaceBalancingRule, _ error) bool {
				return rule.Status.Phase == dbaasv1.PhaseSucceeded ||
					rule.Status.Phase == dbaasv1.PhaseInvalidConfiguration
			},
			"DbNamespaceBalancingRule")
	}()

	rule.Status.Phase = dbaasv1.PhaseProcessing
	rule.Status.LastRequestID = requestID

	if msg, err := r.validateNamespaceRule(ctx, rule); err != nil {
		return ctrl.Result{}, err
	} else if msg != "" {
		return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
	}

	for _, item := range rule.Spec.Rules {
		aggStart := time.Now()
		err = r.Aggregator.ApplyNamespaceBalancingRule(ctx, rule.Namespace, item.Name, namespaceRequestFromSpecItem(item))
		recordAggregatorCall(controllerBR, operationApplyNamespaceRule, aggStart, err)
		if err != nil {
			log.ErrorC(ctx, "failed to apply DbNamespaceBalancingRule to dbaas-aggregator: %v", err)
			return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
		}
	}

	markSucceeded(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, EventReasonBalancingRuleApplied)
	rule.Status.AppliedRules = appliedNamespaceRulesFromSpec(rule.Spec.Rules)
	log.InfoC(ctx, "DbNamespaceBalancingRule applied to dbaas-aggregator namespace=%s name=%s rules=%d requestId=%s",
		rule.Namespace, rule.Name, len(rule.Spec.Rules), requestID)
	r.Recorder.Eventf(rule, corev1.EventTypeNormal, EventReasonBalancingRuleApplied,
		"namespace balancing rules applied to dbaas-aggregator (rules=%d, requestId=%s)",
		len(rule.Spec.Rules), requestID)
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) ReconcilePermanent(ctx context.Context, req ctrl.Request) (result ctrl.Result, retErr error) {
	ctx, requestID := initReconcileContext(ctx)

	rule := &dbaasv1.DbPermanentBalancingRule{}
	if err := r.Get(ctx, req.NamespacedName, rule); err != nil {
		if apierrors.IsNotFound(err) {
			r.clearBindingTrigger(permanentRuleTriggerKey(req.Namespace, req.Name))
		}
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	key := permanentRuleTriggerKey(rule.Namespace, rule.Name)
	trigger := r.triggerForKey(key)

	owned, result, err := checkOwnership(ctx, r.Ownership, rule.Namespace, rule.Name, "DbPermanentBalancingRule")
	if err != nil {
		return ctrl.Result{}, err
	}
	if !owned {
		r.clearBindingTrigger(key)
		return result, nil
	}
	recordReconcileTrigger(controllerBR, trigger)

	if !rule.DeletionTimestamp.IsZero() {
		return r.reconcilePermanentDelete(ctx, rule, requestID)
	}
	if !controllerutil.ContainsFinalizer(rule, dbaasv1.DbPermanentBalancingRuleFinalizer) {
		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.AddFinalizer(rule, dbaasv1.DbPermanentBalancingRuleFinalizer)
		return ctrl.Result{}, r.Patch(ctx, rule, patch)
	}

	original := rule.DeepCopy()
	defer func() {
		patchStatusOnExit(ctx, r.Status(), rule, original, &retErr,
			func(rule *dbaasv1.DbPermanentBalancingRule, _ error) bool {
				return rule.Status.Phase == dbaasv1.PhaseSucceeded ||
					rule.Status.Phase == dbaasv1.PhaseInvalidConfiguration
			},
			"DbPermanentBalancingRule")
	}()

	rule.Status.Phase = dbaasv1.PhaseProcessing
	rule.Status.LastRequestID = requestID

	if msg, err := r.validatePermanentRule(ctx, rule); err != nil {
		return ctrl.Result{}, err
	} else if msg != "" {
		return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
	}
	if result, msg, ok, err := r.checkPermanentRuleTargetOwnership(ctx, rule); err != nil {
		return ctrl.Result{}, err
	} else if !ok {
		if msg != "" {
			return invalidSpec(ctx, &rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, msg)
		}
		rule.Status.Phase = dbaasv1.PhaseWaitingForDependency
		setCondition(&rule.Status.Conditions, rule.Generation,
			conditionTypeReady, metav1.ConditionFalse, EventReasonWaitingForNamespaceBinding,
			"waiting for all target namespaces to be owned by this dbaas-operator instance")
		setCondition(&rule.Status.Conditions, rule.Generation,
			conditionTypeStalled, metav1.ConditionFalse, EventReasonWaitingForNamespaceBinding, stalledMsgTransient)
		return result, nil
	}

	if err := r.cleanupSupersededPermanentRules(ctx, rule); err != nil {
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	aggStart := time.Now()
	err = r.Aggregator.ApplyPermanentBalancingRules(ctx, permanentRequestsFromSpec(rule.Spec.Rules))
	recordAggregatorCall(controllerBR, operationApplyPermanentRule, aggStart, err)
	if err != nil {
		log.ErrorC(ctx, "failed to apply DbPermanentBalancingRule to dbaas-aggregator: %v", err)
		return handleAggregatorError(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, r.Recorder, rule, err, requestID)
	}

	markSucceeded(&rule.Status.Phase, &rule.Status.Conditions, rule.Generation, EventReasonBalancingRuleApplied)
	rule.Status.AppliedRules = appliedPermanentRulesFromSpec(rule.Spec.Rules)
	log.InfoC(ctx, "DbPermanentBalancingRule applied to dbaas-aggregator namespace=%s name=%s rules=%d requestId=%s",
		rule.Namespace, rule.Name, len(rule.Spec.Rules), requestID)
	r.Recorder.Eventf(rule, corev1.EventTypeNormal, EventReasonBalancingRuleApplied,
		"permanent balancing rules applied to dbaas-aggregator (rules=%d, requestId=%s)",
		len(rule.Spec.Rules), requestID)
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) SetupWithManager(mgr ctrl.Manager, opts ctrlcontroller.Options) error {
	if err := ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DbMicroserviceBalancingRule{},
			builder.WithPredicates(generationOrLifecycleChangedPredicate())).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueMicroserviceRulesForBinding)).
		WithOptions(opts).
		Named("dbmicroservicebalancingrule").
		Complete(reconcile.Func(r.ReconcileMicroservice)); err != nil {
		return err
	}

	if err := ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DbNamespaceBalancingRule{},
			builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueueNamespaceRulesForBinding)).
		WithOptions(opts).
		Named("dbnamespacebalancingrule").
		Complete(reconcile.Func(r.ReconcileNamespace)); err != nil {
		return err
	}

	return ctrl.NewControllerManagedBy(mgr).
		For(&dbaasv1.DbPermanentBalancingRule{},
			builder.WithPredicates(generationOrLifecycleChangedPredicate())).
		Watches(&dbaasv1.NamespaceBinding{},
			handler.EnqueueRequestsFromMapFunc(r.enqueuePermanentRulesForBinding)).
		WithOptions(opts).
		Named("dbpermanentbalancingrule").
		Complete(reconcile.Func(r.ReconcilePermanent))
}

func (r *BalancingRuleReconciler) reconcileMicroserviceDelete(
	ctx context.Context,
	rule *dbaasv1.DbMicroserviceBalancingRule,
	requestID string,
) (ctrl.Result, error) {
	if controllerutil.ContainsFinalizer(rule, dbaasv1.DbMicroserviceBalancingRuleFinalizer) {
		for _, applied := range rule.Status.AppliedRules {
			if applied.Type == "" || len(applied.Microservices) == 0 {
				continue
			}
			if err := r.cleanupMicroserviceTargets(ctx, rule.Namespace, applied.Type, applied.Microservices); err != nil {
				r.Recorder.Eventf(rule, corev1.EventTypeWarning, EventReasonAggregatorError,
					"failed to clean up microservice balancing rule during deletion: %s (requestId=%s)",
					err, requestID)
				return ctrl.Result{}, err
			}
		}

		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.RemoveFinalizer(rule, dbaasv1.DbMicroserviceBalancingRuleFinalizer)
		if err := r.Patch(ctx, rule, patch); err != nil {
			return ctrl.Result{}, err
		}
	}
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) reconcilePermanentDelete(
	ctx context.Context,
	rule *dbaasv1.DbPermanentBalancingRule,
	requestID string,
) (ctrl.Result, error) {
	if controllerutil.ContainsFinalizer(rule, dbaasv1.DbPermanentBalancingRuleFinalizer) {
		for _, applied := range rule.Status.AppliedRules {
			if applied.DbType == "" || len(applied.Namespaces) == 0 {
				continue
			}
			if err := r.deletePermanentTargets(ctx, applied.DbType, applied.Namespaces); err != nil {
				r.Recorder.Eventf(rule, corev1.EventTypeWarning, EventReasonAggregatorError,
					"failed to delete permanent balancing rule during deletion: %s (requestId=%s)",
					err, requestID)
				return ctrl.Result{}, err
			}
		}

		patch := client.MergeFrom(rule.DeepCopy())
		controllerutil.RemoveFinalizer(rule, dbaasv1.DbPermanentBalancingRuleFinalizer)
		if err := r.Patch(ctx, rule, patch); err != nil {
			return ctrl.Result{}, err
		}
	}
	return ctrl.Result{}, nil
}

func (r *BalancingRuleReconciler) cleanupSupersededMicroserviceRules(
	ctx context.Context,
	rule *dbaasv1.DbMicroserviceBalancingRule,
) error {
	desired := desiredMicroserviceByType(rule.Spec.Rules)
	for _, applied := range rule.Status.AppliedRules {
		if applied.Type == "" || len(applied.Microservices) == 0 {
			continue
		}
		removed := differenceStrings(applied.Microservices, desired[lower(applied.Type)])
		if len(removed) == 0 {
			continue
		}
		if err := r.cleanupMicroserviceTargets(ctx, rule.Namespace, applied.Type, removed); err != nil {
			return err
		}
	}
	return nil
}

func (r *BalancingRuleReconciler) cleanupSupersededPermanentRules(
	ctx context.Context,
	rule *dbaasv1.DbPermanentBalancingRule,
) error {
	desired := desiredPermanentByDbType(rule.Spec.Rules)
	for _, applied := range rule.Status.AppliedRules {
		if applied.DbType == "" || len(applied.Namespaces) == 0 {
			continue
		}
		removed := differenceStrings(applied.Namespaces, desired[lower(applied.DbType)])
		if len(removed) == 0 {
			continue
		}
		if err := r.deletePermanentTargets(ctx, applied.DbType, removed); err != nil {
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

func (r *BalancingRuleReconciler) deletePermanentTargets(
	ctx context.Context,
	dbType string,
	namespaces []string,
) error {
	reqBody := []aggregatorclient.PermanentBalancingRuleDeleteRequest{{
		DbType:     dbType,
		Namespaces: namespaces,
	}}
	aggStart := time.Now()
	err := r.Aggregator.DeletePermanentBalancingRules(ctx, reqBody)
	recordAggregatorCall(controllerBR, operationDeletePermanentRule, aggStart, err)
	return err
}

func (r *BalancingRuleReconciler) validateMicroserviceRule(ctx context.Context, rule *dbaasv1.DbMicroserviceBalancingRule) (string, error) {
	if rule.Name != dbaasv1.DbMicroserviceBalancingRuleName {
		return fmt.Sprintf("metadata.name must be %q", dbaasv1.DbMicroserviceBalancingRuleName), nil
	}
	if len(rule.Spec.Rules) == 0 {
		return "spec.rules must not be empty", nil
	}
	seen := map[string]struct{}{}
	for i, item := range rule.Spec.Rules {
		if strings.TrimSpace(item.Type) == "" {
			return fmt.Sprintf("spec.rules[%d].type must not be blank", i), nil
		}
		if strings.TrimSpace(item.Label) == "" {
			return fmt.Sprintf("spec.rules[%d].label must not be blank", i), nil
		}
		if !strings.Contains(item.Label, "=") || strings.Count(item.Label, "=") != 1 {
			return fmt.Sprintf("spec.rules[%d].label must be in key=value format", i), nil
		}
		for j, microservice := range item.Microservices {
			if strings.TrimSpace(microservice) == "" {
				return fmt.Sprintf("spec.rules[%d].microservices[%d] must not be blank", i, j), nil
			}
			key := lower(item.Type) + "\x00" + microservice
			if _, ok := seen[key]; ok {
				return fmt.Sprintf("spec.rules contains duplicate microservice %q for type %q", microservice, item.Type), nil
			}
			seen[key] = struct{}{}
		}
	}
	return "", nil
}

func (r *BalancingRuleReconciler) validateNamespaceRule(ctx context.Context, rule *dbaasv1.DbNamespaceBalancingRule) (string, error) {
	if rule.Name != dbaasv1.DbNamespaceBalancingRuleName {
		return fmt.Sprintf("metadata.name must be %q", dbaasv1.DbNamespaceBalancingRuleName), nil
	}
	if len(rule.Spec.Rules) == 0 {
		return "spec.rules must not be empty", nil
	}
	names := map[string]struct{}{}
	typeOrders := map[string]map[int64]struct{}{}
	for i, item := range rule.Spec.Rules {
		if strings.TrimSpace(item.Name) == "" {
			return fmt.Sprintf("spec.rules[%d].name must not be blank", i), nil
		}
		nameKey := lower(item.Name)
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
		typeKey := lower(item.Type)
		if typeOrders[typeKey] == nil {
			typeOrders[typeKey] = map[int64]struct{}{}
		}
		if _, ok := typeOrders[typeKey][item.Order]; ok {
			return fmt.Sprintf("spec.rules contains duplicate order %d for type %q", item.Order, item.Type), nil
		}
		typeOrders[typeKey][item.Order] = struct{}{}
	}
	if r.Client != nil {
		if msg, err := r.validateNamespaceRuleGlobalConflicts(ctx, rule, names, typeOrders); msg != "" || err != nil {
			return msg, err
		}
	}
	return "", nil
}

func (r *BalancingRuleReconciler) validateNamespaceRuleGlobalConflicts(
	ctx context.Context,
	rule *dbaasv1.DbNamespaceBalancingRule,
	names map[string]struct{},
	typeOrders map[string]map[int64]struct{},
) (string, error) {
	list := &dbaasv1.DbNamespaceBalancingRuleList{}
	if err := r.List(ctx, list); err != nil {
		return "", err
	}

	for i := range list.Items {
		other := &list.Items[i]
		if other.Namespace == rule.Namespace && other.Name == rule.Name {
			continue
		}
		for _, item := range other.Spec.Rules {
			if _, ok := names[lower(item.Name)]; ok {
				return fmt.Sprintf("spec.rules contains name %q already managed by DbNamespaceBalancingRule %q/%q", item.Name, other.Namespace, other.Name), nil
			}
			if orders := typeOrders[lower(item.Type)]; orders != nil {
				if _, ok := orders[item.Order]; ok {
					return fmt.Sprintf("spec.rules contains order %d for type %q already managed by DbNamespaceBalancingRule %q/%q", item.Order, item.Type, other.Namespace, other.Name), nil
				}
			}
		}
	}
	return "", nil
}

func (r *BalancingRuleReconciler) validatePermanentRule(ctx context.Context, rule *dbaasv1.DbPermanentBalancingRule) (string, error) {
	if rule.Name != dbaasv1.DbPermanentBalancingRuleName {
		return fmt.Sprintf("metadata.name must be %q", dbaasv1.DbPermanentBalancingRuleName), nil
	}
	if r.MyNamespace != "" && rule.Namespace != r.MyNamespace {
		return fmt.Sprintf("metadata.namespace must be operator namespace %q", r.MyNamespace), nil
	}
	if len(rule.Spec.Rules) == 0 {
		return "spec.rules must not be empty", nil
	}
	seen := map[string]struct{}{}
	for i, item := range rule.Spec.Rules {
		if strings.TrimSpace(item.DbType) == "" {
			return fmt.Sprintf("spec.rules[%d].dbType must not be blank", i), nil
		}
		if strings.TrimSpace(item.PhysicalDatabaseID) == "" {
			return fmt.Sprintf("spec.rules[%d].physicalDatabaseId must not be blank", i), nil
		}
		for j, namespace := range item.Namespaces {
			if strings.TrimSpace(namespace) == "" {
				return fmt.Sprintf("spec.rules[%d].namespaces[%d] must not be blank", i, j), nil
			}
			key := lower(item.DbType) + "\x00" + namespace
			if _, ok := seen[key]; ok {
				return fmt.Sprintf("spec.rules contains duplicate namespace %q for dbType %q", namespace, item.DbType), nil
			}
			seen[key] = struct{}{}
		}
	}
	return "", nil
}

func (r *BalancingRuleReconciler) checkPermanentRuleTargetOwnership(
	ctx context.Context,
	rule *dbaasv1.DbPermanentBalancingRule,
) (ctrl.Result, string, bool, error) {
	for i, item := range rule.Spec.Rules {
		for _, namespace := range item.Namespaces {
			mine, err := r.Ownership.IsMyNamespace(ctx, namespace)
			if err != nil {
				return ctrl.Result{}, "", false, err
			}
			if mine {
				continue
			}
			switch r.Ownership.GetState(namespace) {
			case ownership.Unknown:
				log.InfoC(ctx, "permanent rule %s/%s target namespace %s has no NamespaceBinding yet, will retry in %s",
					rule.Namespace, rule.Name, namespace, ownershipPollInterval)
				return ctrl.Result{RequeueAfter: ownershipPollInterval}, "", false, nil
			case ownership.Unbound:
				log.InfoC(ctx, "permanent rule %s/%s target namespace %s is unbound, will retry in %s",
					rule.Namespace, rule.Name, namespace, ownershipUnboundRetryInterval)
				return ctrl.Result{RequeueAfter: ownershipUnboundRetryInterval}, "", false, nil
			default:
				return ctrl.Result{}, fmt.Sprintf("spec.rules[%d].namespaces contains namespace %q owned by another dbaas-operator instance", i, namespace), false, nil
			}
		}
	}
	return ctrl.Result{}, "", true, nil
}

func (r *BalancingRuleReconciler) enqueueMicroserviceRulesForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	list := &dbaasv1.DbMicroserviceBalancingRuleList{}
	if err := r.List(ctx, list, client.InNamespace(obj.GetNamespace())); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list DbMicroserviceBalancingRules in %s: %v", obj.GetNamespace(), err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		r.stampBindingTrigger(microserviceRuleTriggerKey(list.Items[i].Namespace, list.Items[i].Name))
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}

func (r *BalancingRuleReconciler) enqueueNamespaceRulesForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	list := &dbaasv1.DbNamespaceBalancingRuleList{}
	if err := r.List(ctx, list, client.InNamespace(obj.GetNamespace())); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list DbNamespaceBalancingRules in %s: %v", obj.GetNamespace(), err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		r.stampBindingTrigger(namespaceRuleTriggerKey(list.Items[i].Namespace, list.Items[i].Name))
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}

func (r *BalancingRuleReconciler) enqueuePermanentRulesForBinding(ctx context.Context, obj client.Object) []reconcile.Request {
	list := &dbaasv1.DbPermanentBalancingRuleList{}
	if err := r.List(ctx, list); err != nil {
		log.ErrorC(ctx, "enqueueForBinding: list DbPermanentBalancingRules: %v", err)
		return nil
	}
	reqs := make([]reconcile.Request, 0, len(list.Items))
	for i := range list.Items {
		if list.Items[i].Namespace != obj.GetNamespace() && !permanentRuleTargetsNamespace(&list.Items[i], obj.GetNamespace()) {
			continue
		}
		r.stampBindingTrigger(permanentRuleTriggerKey(list.Items[i].Namespace, list.Items[i].Name))
		reqs = append(reqs, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(&list.Items[i])})
	}
	return reqs
}

func (r *BalancingRuleReconciler) triggerForKey(key string) string {
	if r.consumeBindingTrigger(key) {
		return triggerNamespaceBindingChange
	}
	return triggerSpecChange
}

func (r *BalancingRuleReconciler) stampBindingTrigger(key string) {
	r.bindingTriggerMu.Lock()
	defer r.bindingTriggerMu.Unlock()
	if r.bindingTriggerStamps == nil {
		r.bindingTriggerStamps = make(map[string]struct{})
	}
	r.bindingTriggerStamps[key] = struct{}{}
}

func (r *BalancingRuleReconciler) consumeBindingTrigger(key string) bool {
	r.bindingTriggerMu.Lock()
	defer r.bindingTriggerMu.Unlock()
	if _, ok := r.bindingTriggerStamps[key]; !ok {
		return false
	}
	delete(r.bindingTriggerStamps, key)
	return true
}

func (r *BalancingRuleReconciler) clearBindingTrigger(key string) {
	r.bindingTriggerMu.Lock()
	defer r.bindingTriggerMu.Unlock()
	delete(r.bindingTriggerStamps, key)
}

func microserviceRuleTriggerKey(namespace, name string) string {
	return "microservice/" + namespace + "/" + name
}

func namespaceRuleTriggerKey(namespace, name string) string {
	return "namespace/" + namespace + "/" + name
}

func permanentRuleTriggerKey(namespace, name string) string {
	return "permanent/" + namespace + "/" + name
}

func containsString(values []string, value string) bool {
	for _, current := range values {
		if current == value {
			return true
		}
	}
	return false
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

func lower(value string) string {
	return strings.ToLower(value)
}

func microserviceRequestsFromSpec(
	rules []dbaasv1.DbMicroserviceBalancingRuleItem,
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
	item dbaasv1.DbNamespaceBalancingRuleItem,
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
	rules []dbaasv1.DbPermanentBalancingRuleItem,
) []aggregatorclient.PermanentBalancingRuleRequest {
	reqs := make([]aggregatorclient.PermanentBalancingRuleRequest, 0, len(rules))
	for _, item := range rules {
		reqs = append(reqs, aggregatorclient.PermanentBalancingRuleRequest{
			DbType:             item.DbType,
			PhysicalDatabaseID: item.PhysicalDatabaseID,
			Namespaces:         item.Namespaces,
		})
	}
	return reqs
}

func appliedMicroserviceRulesFromSpec(
	rules []dbaasv1.DbMicroserviceBalancingRuleItem,
) []dbaasv1.DbMicroserviceBalancingRuleAppliedRule {
	applied := make([]dbaasv1.DbMicroserviceBalancingRuleAppliedRule, 0, len(rules))
	for _, rule := range rules {
		applied = append(applied, dbaasv1.DbMicroserviceBalancingRuleAppliedRule{
			Type:          rule.Type,
			Microservices: append([]string(nil), rule.Microservices...),
		})
	}
	return applied
}

func appliedNamespaceRulesFromSpec(
	rules []dbaasv1.DbNamespaceBalancingRuleItem,
) []dbaasv1.DbNamespaceBalancingRuleAppliedRule {
	applied := make([]dbaasv1.DbNamespaceBalancingRuleAppliedRule, 0, len(rules))
	for _, rule := range rules {
		applied = append(applied, dbaasv1.DbNamespaceBalancingRuleAppliedRule{
			Name:               rule.Name,
			Type:               rule.Type,
			PhysicalDatabaseID: rule.PhysicalDatabaseID,
			Order:              rule.Order,
		})
	}
	return applied
}

func appliedPermanentRulesFromSpec(
	rules []dbaasv1.DbPermanentBalancingRuleItem,
) []dbaasv1.DbPermanentBalancingRuleAppliedRule {
	applied := make([]dbaasv1.DbPermanentBalancingRuleAppliedRule, 0, len(rules))
	for _, rule := range rules {
		applied = append(applied, dbaasv1.DbPermanentBalancingRuleAppliedRule{
			DbType:     rule.DbType,
			Namespaces: append([]string(nil), rule.Namespaces...),
		})
	}
	return applied
}

func desiredMicroserviceByType(rules []dbaasv1.DbMicroserviceBalancingRuleItem) map[string][]string {
	desired := make(map[string][]string, len(rules))
	for _, rule := range rules {
		key := lower(rule.Type)
		desired[key] = append(desired[key], rule.Microservices...)
	}
	return desired
}

func desiredPermanentByDbType(rules []dbaasv1.DbPermanentBalancingRuleItem) map[string][]string {
	desired := make(map[string][]string, len(rules))
	for _, rule := range rules {
		key := lower(rule.DbType)
		desired[key] = append(desired[key], rule.Namespaces...)
	}
	return desired
}

func permanentRuleTargetsNamespace(rule *dbaasv1.DbPermanentBalancingRule, namespace string) bool {
	for _, item := range rule.Spec.Rules {
		if containsString(item.Namespaces, namespace) {
			return true
		}
	}
	return false
}

func copyInt64Ptr(value *int64) *int64 {
	if value == nil {
		return nil
	}
	copied := *value
	return &copied
}
