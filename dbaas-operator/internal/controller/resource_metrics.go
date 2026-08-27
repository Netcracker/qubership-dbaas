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
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/metrics"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

const (
	resourceKindExternalDatabase           = "ExternalDatabase"
	resourceKindInternalDatabase           = "InternalDatabase"
	resourceKindDatabaseAccessPolicy       = "DatabaseAccessPolicy"
	resourceKindDatabaseSecretClaim        = "DatabaseSecretClaim"
	resourceKindMicroserviceBalancingRule  = "MicroserviceBalancingRule"
	resourceKindNamespaceBalancingRule     = "NamespaceBalancingRule"
	resourceKindPermanentBalancingRule     = "PermanentBalancingRule"
	resourceKindNamespaceBinding           = "NamespaceBinding"
	namespaceBindingStateMine              = "mine"
	namespaceBindingStateForeign           = "foreign"
	namespaceBindingStateDeleting          = "deleting"
	namespaceBindingStateDeletingFinalizer = "deleting_with_finalizer"
	resourceDeletionStateDeleting          = "deleting"
	resourceDeletionStateDeletingFinalizer = "deleting_with_finalizer"
	balancingTargetTypeMicroservice        = "microservice"
	balancingTargetTypeRule                = "rule"
	balancingTargetTypeNamespace           = "namespace"
	resourceMetricsCollectionTimeout       = 10 * time.Second
)

var (
	resourcePhaseDesc = prometheus.NewDesc(
		"dbaas_resource_phase",
		"Current phase of dbaas operator resources that expose OperatorStatus. Emits one series for the current phase of each resource owned by this operator instance.",
		[]string{"kind", "resource_namespace", "name", "phase"},
		nil,
	)
	resourceConditionDesc = prometheus.NewDesc(
		"dbaas_resource_condition",
		"Current status conditions of dbaas operator resources that expose OperatorStatus. Emits current condition type, status, and reason for each resource owned by this operator instance.",
		[]string{"kind", "resource_namespace", "name", "condition", "status", "reason"},
		nil,
	)
	resourceGenerationLagDesc = prometheus.NewDesc(
		"dbaas_resource_observed_generation_lag",
		"Difference between metadata.generation and status.observedGeneration for dbaas operator resources owned by this operator instance.",
		[]string{"kind", "resource_namespace", "name"},
		nil,
	)
	namespaceBindingStateDesc = prometheus.NewDesc(
		"dbaas_namespace_binding_state",
		"Current NamespaceBinding state from this operator instance's point of view. deleting_with_finalizer means deletion is in progress and the protection finalizer is still present; it does not prove blocking resources still exist.",
		[]string{"resource_namespace", "name", "state"},
		nil,
	)
	resourceDeletionStateDesc = prometheus.NewDesc(
		"dbaas_resource_deletion_state",
		"Current deletion state for dbaas operator resources. deleting_with_finalizer means deletion is in progress and at least one finalizer is still present.",
		[]string{"kind", "resource_namespace", "name", "state"},
		nil,
	)
	balancingRuleDesiredTargetsDesc = prometheus.NewDesc(
		"dbaas_balancing_rule_desired_targets",
		"Current desired balancing-rule target count from spec. This is operator intent only; it does not prove the referenced physical database exists or that dbaas-aggregator has applied the rule.",
		[]string{"kind", "resource_namespace", "name", "target_type"},
		nil,
	)
	balancingRuleAppliedTargetsDesc = prometheus.NewDesc(
		"dbaas_balancing_rule_applied_targets",
		"Current applied balancing-rule target count recorded in status. This reflects the last operator-applied state, not an independent readback from dbaas-aggregator or a guarantee that referenced physical databases still exist.",
		[]string{"kind", "resource_namespace", "name", "target_type"},
		nil,
	)
	secretClaimLastRotationTimestampDesc = prometheus.NewDesc(
		"dbaas_secret_claim_last_rotation_timestamp_seconds",
		"Unix timestamp of the most recent connection-properties rotation applied by a DatabaseSecretClaim. Absence or old values do not by themselves prove the rotation poller is broken; the controller also has a per-CR safety-net reconcile.",
		[]string{"resource_namespace", "name"},
		nil,
	)
	secretClaimFirstNotFoundTimestampDesc = prometheus.NewDesc(
		"dbaas_secret_claim_first_not_found_timestamp_seconds",
		"Unix timestamp when the current DatabaseNotFound streak started for a DatabaseSecretClaim.",
		[]string{"resource_namespace", "name"},
		nil,
	)
	resourceCollectorSuccessDesc = prometheus.NewDesc(
		"dbaas_resource_collector_success",
		"Whether the latest resource metrics collection for a CR kind succeeded.",
		[]string{"kind"},
		nil,
	)
	registerResourceMetricsOnce sync.Once
)

// RegisterResourceMetrics registers kube-state-style current-state metrics for
// dbaas CRs. The metrics intentionally expose CR name only on gauges that
// represent current state; counters and histograms remain low-cardinality.
func RegisterResourceMetrics(c client.Client, resolver *ownership.OwnershipResolver, operatorNamespace string) {
	registerResourceMetricsOnce.Do(func() {
		metrics.Registry.MustRegister(&resourceMetricsCollector{
			client:            c,
			ownership:         resolver,
			operatorNamespace: operatorNamespace,
		})
	})
}

type resourceMetricsCollector struct {
	client            client.Client
	ownership         *ownership.OwnershipResolver
	operatorNamespace string
}

func (c *resourceMetricsCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- resourcePhaseDesc
	ch <- resourceConditionDesc
	ch <- resourceGenerationLagDesc
	ch <- namespaceBindingStateDesc
	ch <- resourceDeletionStateDesc
	ch <- balancingRuleDesiredTargetsDesc
	ch <- balancingRuleAppliedTargetsDesc
	ch <- secretClaimLastRotationTimestampDesc
	ch <- secretClaimFirstNotFoundTimestampDesc
	ch <- resourceCollectorSuccessDesc
}

func (c *resourceMetricsCollector) Collect(ch chan<- prometheus.Metric) {
	ctx, cancel := context.WithTimeout(context.Background(), resourceMetricsCollectionTimeout)
	defer cancel()

	c.collectExternalDatabases(ctx, ch)
	c.collectInternalDatabases(ctx, ch)
	c.collectDatabaseAccessPolicies(ctx, ch)
	c.collectDatabaseSecretClaims(ctx, ch)
	c.collectMicroserviceBalancingRules(ctx, ch)
	c.collectNamespaceBalancingRules(ctx, ch)
	c.collectPermanentBalancingRules(ctx, ch)
	c.collectNamespaceBindings(ctx, ch)
}

func (c *resourceMetricsCollector) collectExternalDatabases(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.ExternalDatabaseList{}
	if !c.collectList(ctx, ch, resourceKindExternalDatabase, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if c.ownsNamespace(item.Namespace) {
			collectOperatorStatus(ch, resourceKindExternalDatabase, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
			collectDeletionState(ch, resourceKindExternalDatabase, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		}
	}
}

func (c *resourceMetricsCollector) collectInternalDatabases(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.InternalDatabaseList{}
	if !c.collectList(ctx, ch, resourceKindInternalDatabase, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if c.ownsNamespace(item.Namespace) {
			collectOperatorStatus(ch, resourceKindInternalDatabase, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
			collectDeletionState(ch, resourceKindInternalDatabase, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		}
	}
}

func (c *resourceMetricsCollector) collectDatabaseAccessPolicies(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.DatabaseAccessPolicyList{}
	if !c.collectList(ctx, ch, resourceKindDatabaseAccessPolicy, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if c.ownsNamespace(item.Namespace) {
			collectOperatorStatus(ch, resourceKindDatabaseAccessPolicy, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
			collectDeletionState(ch, resourceKindDatabaseAccessPolicy, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		}
	}
}

func (c *resourceMetricsCollector) collectDatabaseSecretClaims(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.DatabaseSecretClaimList{}
	if !c.collectList(ctx, ch, resourceKindDatabaseSecretClaim, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if !c.ownsNamespace(item.Namespace) {
			continue
		}
		collectOperatorStatus(ch, resourceKindDatabaseSecretClaim, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
		collectDeletionState(ch, resourceKindDatabaseSecretClaim, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		if item.Status.LastRotatedAt != nil {
			ch <- prometheus.MustNewConstMetric(
				secretClaimLastRotationTimestampDesc,
				prometheus.GaugeValue,
				float64(item.Status.LastRotatedAt.Unix()),
				item.Namespace,
				item.Name,
			)
		}
		if item.Status.FirstNotFoundAt != nil {
			ch <- prometheus.MustNewConstMetric(
				secretClaimFirstNotFoundTimestampDesc,
				prometheus.GaugeValue,
				float64(item.Status.FirstNotFoundAt.Unix()),
				item.Namespace,
				item.Name,
			)
		}
	}
}

func (c *resourceMetricsCollector) collectMicroserviceBalancingRules(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.MicroserviceBalancingRuleList{}
	if !c.collectList(ctx, ch, resourceKindMicroserviceBalancingRule, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if !c.ownsNamespace(item.Namespace) {
			continue
		}
		collectOperatorStatus(ch, resourceKindMicroserviceBalancingRule, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
		collectDeletionState(ch, resourceKindMicroserviceBalancingRule, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		ch <- prometheus.MustNewConstMetric(
			balancingRuleDesiredTargetsDesc,
			prometheus.GaugeValue,
			float64(microserviceDesiredTargetCount(item.Spec.Rules)),
			resourceKindMicroserviceBalancingRule,
			item.Namespace,
			item.Name,
			balancingTargetTypeMicroservice,
		)
		ch <- prometheus.MustNewConstMetric(
			balancingRuleAppliedTargetsDesc,
			prometheus.GaugeValue,
			float64(microserviceAppliedTargetCount(item.Status.AppliedRules)),
			resourceKindMicroserviceBalancingRule,
			item.Namespace,
			item.Name,
			balancingTargetTypeMicroservice,
		)
	}
}

func (c *resourceMetricsCollector) collectNamespaceBalancingRules(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.NamespaceBalancingRuleList{}
	if !c.collectList(ctx, ch, resourceKindNamespaceBalancingRule, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if !c.ownsNamespace(item.Namespace) {
			continue
		}
		collectOperatorStatus(ch, resourceKindNamespaceBalancingRule, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
		collectDeletionState(ch, resourceKindNamespaceBalancingRule, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		ch <- prometheus.MustNewConstMetric(
			balancingRuleDesiredTargetsDesc,
			prometheus.GaugeValue,
			float64(len(item.Spec.Rules)),
			resourceKindNamespaceBalancingRule,
			item.Namespace,
			item.Name,
			balancingTargetTypeRule,
		)
		ch <- prometheus.MustNewConstMetric(
			balancingRuleAppliedTargetsDesc,
			prometheus.GaugeValue,
			float64(len(item.Status.AppliedRules)),
			resourceKindNamespaceBalancingRule,
			item.Namespace,
			item.Name,
			balancingTargetTypeRule,
		)
	}
}

func (c *resourceMetricsCollector) collectPermanentBalancingRules(ctx context.Context, ch chan<- prometheus.Metric) {
	if c.operatorNamespace == "" {
		ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 0, resourceKindPermanentBalancingRule)
		return
	}
	list := &dbaasv1.PermanentBalancingRuleList{}
	if err := c.client.List(ctx, list, client.InNamespace(c.operatorNamespace)); err != nil {
		ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 0, resourceKindPermanentBalancingRule)
		return
	}
	ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 1, resourceKindPermanentBalancingRule)
	for i := range list.Items {
		item := &list.Items[i]
		collectOperatorStatus(ch, resourceKindPermanentBalancingRule, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
		collectDeletionState(ch, resourceKindPermanentBalancingRule, item.Namespace, item.Name, item.DeletionTimestamp, item.Finalizers)
		ch <- prometheus.MustNewConstMetric(
			balancingRuleDesiredTargetsDesc,
			prometheus.GaugeValue,
			float64(permanentDesiredTargetCount(item.Spec.Rules)),
			resourceKindPermanentBalancingRule,
			item.Namespace,
			item.Name,
			balancingTargetTypeNamespace,
		)
		ch <- prometheus.MustNewConstMetric(
			balancingRuleAppliedTargetsDesc,
			prometheus.GaugeValue,
			float64(permanentAppliedTargetCount(item.Status.AppliedRules)),
			resourceKindPermanentBalancingRule,
			item.Namespace,
			item.Name,
			balancingTargetTypeNamespace,
		)
	}
}

func (c *resourceMetricsCollector) collectNamespaceBindings(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.NamespaceBindingList{}
	if !c.collectList(ctx, ch, resourceKindNamespaceBinding, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		state := namespaceBindingState(item, c.operatorNamespace)
		ch <- prometheus.MustNewConstMetric(
			namespaceBindingStateDesc,
			prometheus.GaugeValue,
			1,
			item.Namespace,
			item.Name,
			state,
		)
		// Status gauges only for bindings this instance owns — only the owning
		// instance writes NamespaceBinding status, so a foreign binding's status
		// is another instance's (or nobody's) data. Ownership comes from the
		// binding's own spec, not the resolver cache: the binding IS the source
		// the cache is built from. Deletion state is deliberately not emitted
		// under dbaas_resource_deletion_state — dbaas_namespace_binding_state
		// already carries it (deleting_with_finalizer).
		if item.Spec.OperatorNamespace == c.operatorNamespace {
			collectOperatorStatus(ch, resourceKindNamespaceBinding, item.Namespace, item.Name, item.Generation, item.Status.OperatorStatus)
		}
	}
}

func (c *resourceMetricsCollector) collectList(
	ctx context.Context,
	ch chan<- prometheus.Metric,
	kind string,
	list client.ObjectList,
) bool {
	if err := c.client.List(ctx, list); err != nil {
		ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 0, kind)
		return false
	}
	ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 1, kind)
	return true
}

func (c *resourceMetricsCollector) ownsNamespace(namespace string) bool {
	if c.ownership == nil {
		return false
	}
	return c.ownership.GetState(namespace) == ownership.Mine
}

func collectOperatorStatus(
	ch chan<- prometheus.Metric,
	kind string,
	namespace string,
	name string,
	generation int64,
	status dbaasv1.OperatorStatus,
) {
	phase := string(status.Phase)
	if phase == "" {
		phase = string(dbaasv1.PhaseUnknown)
	}
	ch <- prometheus.MustNewConstMetric(
		resourcePhaseDesc,
		prometheus.GaugeValue,
		1,
		kind,
		namespace,
		name,
		phase,
	)
	ch <- prometheus.MustNewConstMetric(
		resourceGenerationLagDesc,
		prometheus.GaugeValue,
		float64(generationLag(generation, status.ObservedGeneration)),
		kind,
		namespace,
		name,
	)
	seenConditions := make(map[string]struct{}, len(status.Conditions))
	for _, condition := range status.Conditions {
		if _, ok := seenConditions[condition.Type]; ok {
			continue
		}
		seenConditions[condition.Type] = struct{}{}
		ch <- prometheus.MustNewConstMetric(
			resourceConditionDesc,
			prometheus.GaugeValue,
			1,
			kind,
			namespace,
			name,
			condition.Type,
			string(condition.Status),
			condition.Reason,
		)
	}
}

func generationLag(generation, observedGeneration int64) int64 {
	if generation <= observedGeneration {
		return 0
	}
	return generation - observedGeneration
}

func namespaceBindingState(binding *dbaasv1.NamespaceBinding, operatorNamespace string) string {
	if !binding.DeletionTimestamp.IsZero() {
		if controllerutil.ContainsFinalizer(binding, dbaasv1.NamespaceBindingProtectionFinalizer) {
			return namespaceBindingStateDeletingFinalizer
		}
		return namespaceBindingStateDeleting
	}
	if binding.Spec.OperatorNamespace == operatorNamespace {
		return namespaceBindingStateMine
	}
	return namespaceBindingStateForeign
}

func collectDeletionState(
	ch chan<- prometheus.Metric,
	kind string,
	namespace string,
	name string,
	deletionTimestamp *metav1.Time,
	finalizers []string,
) {
	if deletionTimestamp == nil || deletionTimestamp.IsZero() {
		return
	}
	state := resourceDeletionStateDeleting
	if len(finalizers) > 0 {
		state = resourceDeletionStateDeletingFinalizer
	}
	ch <- prometheus.MustNewConstMetric(
		resourceDeletionStateDesc,
		prometheus.GaugeValue,
		1,
		kind,
		namespace,
		name,
		state,
	)
}

func microserviceDesiredTargetCount(rules []dbaasv1.MicroserviceBalancingRuleItem) int {
	count := 0
	for _, rule := range rules {
		count += len(rule.Microservices)
	}
	return count
}

func microserviceAppliedTargetCount(rules []dbaasv1.MicroserviceBalancingRuleAppliedRule) int {
	count := 0
	for _, rule := range rules {
		count += len(rule.Microservices)
	}
	return count
}

func permanentDesiredTargetCount(rules []dbaasv1.PermanentBalancingRuleItem) int {
	count := 0
	for _, rule := range rules {
		count += len(rule.Namespaces)
	}
	return count
}

func permanentAppliedTargetCount(rules []dbaasv1.PermanentBalancingRuleAppliedRule) int {
	count := 0
	for _, rule := range rules {
		count += len(rule.Namespaces)
	}
	return count
}

var _ prometheus.Collector = (*resourceMetricsCollector)(nil)
