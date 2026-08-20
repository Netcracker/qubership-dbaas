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
	"k8s.io/apimachinery/pkg/fields"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/metrics"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

const (
	resourceKindExternalDatabase           = "ExternalDatabase"
	resourceKindInternalDatabase           = "InternalDatabase"
	resourceKindDatabaseAccessPolicy       = "DatabaseAccessPolicy"
	resourceKindDatabaseSecretClaim        = "DatabaseSecretClaim"
	resourceKindMicroserviceBalancingRule  = "MicroserviceBalancingRule"
	resourceKindNamespaceBalancingRule     = "NamespaceBalancingRule"
	resourceKindPermanentBalancingRule     = "PermanentBalancingRule"
	resourceDeletionStateDeleting          = "deleting"
	resourceDeletionStateDeletingFinalizer = "deleting_with_finalizer"
	balancingTargetTypeMicroservice        = "microservice"
	balancingTargetTypeRule                = "rule"
	balancingTargetTypeNamespace           = "namespace"
	resourceMetricsCollectionTimeout       = 10 * time.Second
	// unassignedListPageSize bounds each page of the orphan scan so a cluster with
	// many mis-assigned CRs cannot return an unbounded response in one call.
	unassignedListPageSize = 500
)

var (
	resourcePhaseDesc = prometheus.NewDesc(
		"dbaas_resource_phase",
		"Current phase of dbaas operator resources that expose OperatorStatus. Emits one series for the current phase of each resource assigned to this operator instance.",
		[]string{"kind", "resource_namespace", "name", "phase"},
		nil,
	)
	resourceConditionDesc = prometheus.NewDesc(
		"dbaas_resource_condition",
		"Current status conditions of dbaas operator resources that expose OperatorStatus. Emits current condition type, status, and reason for each resource assigned to this operator instance.",
		[]string{"kind", "resource_namespace", "name", "condition", "status", "reason"},
		nil,
	)
	resourceGenerationLagDesc = prometheus.NewDesc(
		"dbaas_resource_observed_generation_lag",
		"Difference between metadata.generation and status.observedGeneration for dbaas operator resources assigned to this operator instance.",
		[]string{"kind", "resource_namespace", "name"},
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
	resourceUnassignedDesc = prometheus.NewDesc(
		"dbaas_resource_unassigned",
		"A managed CR whose spec.operatorNamespace does not match this operator instance's CLOUD_NAMESPACE, so no reconcile, status, event, or state gauge is produced for it. Value is always 1, one series per such CR. This assumes one operator per cluster: with several operators a CR assigned to a different operator also shows up here.",
		[]string{"kind", "resource_namespace", "name"},
		nil,
	)
	resourceUnassignedScanSuccessDesc = prometheus.NewDesc(
		"dbaas_resource_unassigned_scan_success",
		"Whether the latest orphan scan (the uncached list behind dbaas_resource_unassigned) succeeded for a CR kind. 0 means the scan failed, so dbaas_resource_unassigned for that kind may be missing orphans — the alert on it can otherwise fail open.",
		[]string{"kind"},
		nil,
	)
	registerResourceMetricsOnce sync.Once
)

// RegisterResourceMetrics registers kube-state-style current-state metrics for
// dbaas CRs. The metrics intentionally expose CR name only on gauges that
// represent current state; counters and histograms remain low-cardinality.
//
// reader is an uncached client.Reader (the manager's API reader). The manager's
// cache is filtered to CRs assigned to this operator, so the cached client cannot
// see mis-assigned CRs; the reader lists them directly for dbaas_resource_unassigned.
func RegisterResourceMetrics(c client.Client, reader client.Reader, operatorNamespace string) {
	registerResourceMetricsOnce.Do(func() {
		metrics.Registry.MustRegister(&resourceMetricsCollector{
			client:            c,
			reader:            reader,
			operatorNamespace: operatorNamespace,
		})
	})
}

type resourceMetricsCollector struct {
	client            client.Client
	reader            client.Reader
	operatorNamespace string
}

func (c *resourceMetricsCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- resourcePhaseDesc
	ch <- resourceConditionDesc
	ch <- resourceGenerationLagDesc
	ch <- resourceDeletionStateDesc
	ch <- balancingRuleDesiredTargetsDesc
	ch <- balancingRuleAppliedTargetsDesc
	ch <- secretClaimLastRotationTimestampDesc
	ch <- secretClaimFirstNotFoundTimestampDesc
	ch <- resourceCollectorSuccessDesc
	ch <- resourceUnassignedDesc
	ch <- resourceUnassignedScanSuccessDesc
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
	c.collectUnassigned(ctx, ch)
}

// collectUnassigned scans every managed kind for CRs this operator does not own.
// The manager cache is filtered to owned CRs, so a mis-assigned CR is invisible to
// the cached client; the uncached reader with a server-side field selector lists
// only the orphans, giving the otherwise-silent "assigned to nobody / a typo" case
// an observable signal.
func (c *resourceMetricsCollector) collectUnassigned(ctx context.Context, ch chan<- prometheus.Metric) {
	collectUnassignedKind(c, ctx, ch, resourceKindExternalDatabase, &dbaasv1.ExternalDatabaseList{},
		func(l *dbaasv1.ExternalDatabaseList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
	collectUnassignedKind(c, ctx, ch, resourceKindInternalDatabase, &dbaasv1.InternalDatabaseList{},
		func(l *dbaasv1.InternalDatabaseList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
	collectUnassignedKind(c, ctx, ch, resourceKindDatabaseAccessPolicy, &dbaasv1.DatabaseAccessPolicyList{},
		func(l *dbaasv1.DatabaseAccessPolicyList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
	collectUnassignedKind(c, ctx, ch, resourceKindDatabaseSecretClaim, &dbaasv1.DatabaseSecretClaimList{},
		func(l *dbaasv1.DatabaseSecretClaimList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
	collectUnassignedKind(c, ctx, ch, resourceKindMicroserviceBalancingRule, &dbaasv1.MicroserviceBalancingRuleList{},
		func(l *dbaasv1.MicroserviceBalancingRuleList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
	collectUnassignedKind(c, ctx, ch, resourceKindNamespaceBalancingRule, &dbaasv1.NamespaceBalancingRuleList{},
		func(l *dbaasv1.NamespaceBalancingRuleList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
	collectUnassignedKind(c, ctx, ch, resourceKindPermanentBalancingRule, &dbaasv1.PermanentBalancingRuleList{},
		func(l *dbaasv1.PermanentBalancingRuleList) []unassignedRow {
			rows := make([]unassignedRow, len(l.Items))
			for i := range l.Items {
				rows[i] = unassignedRow{l.Items[i].Namespace, l.Items[i].Name}
			}
			return rows
		})
}

type unassignedRow struct {
	namespace, name string
}

// collectUnassignedKind lists one kind through the uncached reader with a
// server-side spec.operatorNamespace != CLOUD_NAMESPACE field selector, so the API
// server returns only the CRs this operator does not own rather than the whole
// kind. Results are paged. A list failure sets the per-kind scan-success gauge to 0
// so an alert on dbaas_resource_unassigned cannot silently fail open.
func collectUnassignedKind[L client.ObjectList](
	c *resourceMetricsCollector,
	ctx context.Context,
	ch chan<- prometheus.Metric,
	kind string,
	list L,
	extract func(L) []unassignedRow,
) {
	selector := fields.OneTermNotEqualSelector("spec.operatorNamespace", c.operatorNamespace)
	continueToken := ""
	for {
		opts := []client.ListOption{
			client.MatchingFieldsSelector{Selector: selector},
			client.Limit(unassignedListPageSize),
		}
		if continueToken != "" {
			opts = append(opts, client.Continue(continueToken))
		}
		if err := c.reader.List(ctx, list, opts...); err != nil {
			ch <- prometheus.MustNewConstMetric(resourceUnassignedScanSuccessDesc, prometheus.GaugeValue, 0, kind)
			return
		}
		for _, row := range extract(list) {
			ch <- prometheus.MustNewConstMetric(
				resourceUnassignedDesc, prometheus.GaugeValue, 1, kind, row.namespace, row.name)
		}
		continueToken = list.GetContinue()
		if continueToken == "" {
			break
		}
	}
	ch <- prometheus.MustNewConstMetric(resourceUnassignedScanSuccessDesc, prometheus.GaugeValue, 1, kind)
}

func (c *resourceMetricsCollector) collectExternalDatabases(ctx context.Context, ch chan<- prometheus.Metric) {
	list := &dbaasv1.ExternalDatabaseList{}
	if !c.collectList(ctx, ch, resourceKindExternalDatabase, list) {
		return
	}
	for i := range list.Items {
		item := &list.Items[i]
		if c.manages(item.Spec.OperatorNamespace) {
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
		if c.manages(item.Spec.OperatorNamespace) {
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
		if c.manages(item.Spec.OperatorNamespace) {
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
		if !c.manages(item.Spec.OperatorNamespace) {
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
		if !c.manages(item.Spec.OperatorNamespace) {
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
		if !c.manages(item.Spec.OperatorNamespace) {
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
	list := &dbaasv1.PermanentBalancingRuleList{}
	if err := c.client.List(ctx, list); err != nil {
		ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 0, resourceKindPermanentBalancingRule)
		return
	}
	ch <- prometheus.MustNewConstMetric(resourceCollectorSuccessDesc, prometheus.GaugeValue, 1, resourceKindPermanentBalancingRule)
	for i := range list.Items {
		item := &list.Items[i]
		if !c.manages(item.Spec.OperatorNamespace) {
			continue
		}
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

func (c *resourceMetricsCollector) manages(operatorNamespace string) bool {
	return dbaasv1.IsAssignedTo(operatorNamespace, c.operatorNamespace)
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
