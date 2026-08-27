package controller

import (
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

func TestGenerationLag(t *testing.T) {
	tests := []struct {
		name               string
		generation         int64
		observedGeneration int64
		want               int64
	}{
		{name: "caught up", generation: 5, observedGeneration: 5, want: 0},
		{name: "not caught up", generation: 6, observedGeneration: 5, want: 1},
		{name: "never negative", generation: 5, observedGeneration: 6, want: 0},
		{name: "new object not observed", generation: 1, observedGeneration: 0, want: 1},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := generationLag(tt.generation, tt.observedGeneration); got != tt.want {
				t.Fatalf("generationLag() = %d, want %d", got, tt.want)
			}
		})
	}
}

func TestCollectOperatorStatusDeduplicatesConditionsByType(t *testing.T) {
	collector := duplicateConditionCollector{}
	registry := prometheus.NewRegistry()
	registry.MustRegister(collector)

	metrics, err := registry.Gather()
	if err != nil {
		t.Fatalf("Gather() returned error for duplicate conditions: %v", err)
	}

	var conditionCount int
	for _, metricFamily := range metrics {
		if metricFamily.GetName() == "dbaas_resource_condition" {
			conditionCount = len(metricFamily.GetMetric())
		}
	}
	if conditionCount != 1 {
		t.Fatalf("dbaas_resource_condition count = %d, want 1", conditionCount)
	}
}

func TestResourceMetricsCollectorFiltersByOwnedNamespace(t *testing.T) {
	scheme := testResourceMetricsScheme(t)
	cl := fake.NewClientBuilder().
		WithScheme(scheme).
		WithObjects(
			&dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: "owned-db", Namespace: "owned-ns", Generation: 1},
				Status:     dbaasv1.ExternalDatabaseStatus{OperatorStatus: dbaasv1.OperatorStatus{Phase: dbaasv1.PhaseSucceeded}},
			},
			&dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: "foreign-db", Namespace: "foreign-ns", Generation: 1},
				Status:     dbaasv1.ExternalDatabaseStatus{OperatorStatus: dbaasv1.OperatorStatus{Phase: dbaasv1.PhaseSucceeded}},
			},
		).
		Build()
	resolver := ownership.NewOwnershipResolver("operator-ns", cl)
	resolver.SetOwner("owned-ns", "operator-ns")
	resolver.SetOwner("foreign-ns", "other-operator-ns")

	metrics := gatherResourceMetrics(t, &resourceMetricsCollector{
		client:            cl,
		ownership:         resolver,
		operatorNamespace: "operator-ns",
	})

	if got := countMetrics(metrics, "dbaas_resource_phase", map[string]string{"kind": resourceKindExternalDatabase}); got != 1 {
		t.Fatalf("ExternalDatabase phase metrics = %d, want 1", got)
	}
	if got := countMetrics(metrics, "dbaas_resource_phase", map[string]string{"resource_namespace": "foreign-ns"}); got != 0 {
		t.Fatalf("foreign namespace phase metrics = %d, want 0", got)
	}
}

func TestResourceMetricsCollectorReportsListErrors(t *testing.T) {
	cl := fake.NewClientBuilder().Build()

	metrics := gatherResourceMetrics(t, &resourceMetricsCollector{
		client:            cl,
		ownership:         ownership.NewOwnershipResolver("operator-ns", cl),
		operatorNamespace: "operator-ns",
	})

	if got := metricValue(metrics, "dbaas_resource_collector_success", map[string]string{"kind": resourceKindExternalDatabase}); got != 0 {
		t.Fatalf("ExternalDatabase collector success = %v, want 0", got)
	}
}

func TestPermanentBalancingRuleCollectorRequiresOperatorNamespace(t *testing.T) {
	scheme := testResourceMetricsScheme(t)
	cl := fake.NewClientBuilder().
		WithScheme(scheme).
		WithObjects(&dbaasv1.PermanentBalancingRule{
			ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.PermanentBalancingRuleName, Namespace: "operator-ns", Generation: 1},
			Status:     dbaasv1.PermanentBalancingRuleStatus{OperatorStatus: dbaasv1.OperatorStatus{Phase: dbaasv1.PhaseSucceeded}},
		}).
		Build()

	metrics := gatherResourceMetrics(t, &resourceMetricsCollector{
		client:    cl,
		ownership: ownership.NewOwnershipResolver("", cl),
	})

	if got := metricValue(metrics, "dbaas_resource_collector_success", map[string]string{"kind": resourceKindPermanentBalancingRule}); got != 0 {
		t.Fatalf("PermanentBalancingRule collector success = %v, want 0", got)
	}
	if got := countMetrics(metrics, "dbaas_resource_phase", map[string]string{"kind": resourceKindPermanentBalancingRule}); got != 0 {
		t.Fatalf("PermanentBalancingRule phase metrics = %d, want 0", got)
	}
}

func testResourceMetricsScheme(t *testing.T) *runtime.Scheme {
	t.Helper()
	scheme := runtime.NewScheme()
	if err := dbaasv1.AddToScheme(scheme); err != nil {
		t.Fatalf("AddToScheme() error = %v", err)
	}
	return scheme
}

func gatherResourceMetrics(t *testing.T, collector prometheus.Collector) []*dto.MetricFamily {
	t.Helper()
	registry := prometheus.NewRegistry()
	registry.MustRegister(collector)
	metrics, err := registry.Gather()
	if err != nil {
		t.Fatalf("Gather() error = %v", err)
	}
	return metrics
}

func countMetrics(metrics []*dto.MetricFamily, name string, labels map[string]string) int {
	var count int
	for _, family := range metrics {
		if family.GetName() != name {
			continue
		}
		for _, metric := range family.GetMetric() {
			if metricHasLabels(metric, labels) {
				count++
			}
		}
	}
	return count
}

func metricValue(metrics []*dto.MetricFamily, name string, labels map[string]string) float64 {
	for _, family := range metrics {
		if family.GetName() != name {
			continue
		}
		for _, metric := range family.GetMetric() {
			if metricHasLabels(metric, labels) {
				return metric.GetGauge().GetValue()
			}
		}
	}
	return -1
}

func metricHasLabels(metric *dto.Metric, want map[string]string) bool {
	for key, value := range want {
		found := false
		for _, label := range metric.GetLabel() {
			if label.GetName() == key && label.GetValue() == value {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}

type duplicateConditionCollector struct{}

func (duplicateConditionCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- resourcePhaseDesc
	ch <- resourceGenerationLagDesc
	ch <- resourceConditionDesc
}

func (duplicateConditionCollector) Collect(ch chan<- prometheus.Metric) {
	collectOperatorStatus(ch, resourceKindExternalDatabase, "ns", "db", 1, dbaasv1.OperatorStatus{
		Phase:              dbaasv1.PhaseProcessing,
		ObservedGeneration: 1,
		Conditions: []metav1.Condition{
			{Type: "Ready", Status: metav1.ConditionFalse, Reason: "BackingOff"},
			{Type: "Ready", Status: metav1.ConditionFalse, Reason: "BackingOff"},
		},
	})
}

func TestNamespaceBindingState(t *testing.T) {
	now := metav1.Now()

	tests := []struct {
		name string
		nb   *dbaasv1.NamespaceBinding
		want string
	}{
		{
			name: "mine",
			nb: &dbaasv1.NamespaceBinding{
				Spec: dbaasv1.NamespaceBindingSpec{OperatorNamespace: "operator-ns"},
			},
			want: namespaceBindingStateMine,
		},
		{
			name: "foreign",
			nb: &dbaasv1.NamespaceBinding{
				Spec: dbaasv1.NamespaceBindingSpec{OperatorNamespace: "other-ns"},
			},
			want: namespaceBindingStateForeign,
		},
		{
			name: "deleting",
			nb: &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{DeletionTimestamp: &now},
			},
			want: namespaceBindingStateDeleting,
		},
		{
			name: "deleting with finalizer",
			nb: &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{
					DeletionTimestamp: &now,
					Finalizers:        []string{dbaasv1.NamespaceBindingProtectionFinalizer},
				},
			},
			want: namespaceBindingStateDeletingFinalizer,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := namespaceBindingState(tt.nb, "operator-ns"); got != tt.want {
				t.Fatalf("namespaceBindingState() = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestBalancingRuleAppliedTargetCounts(t *testing.T) {
	if got := microserviceDesiredTargetCount([]dbaasv1.MicroserviceBalancingRuleItem{
		{Microservices: []string{"a", "b"}},
		{Microservices: []string{"c"}},
	}); got != 3 {
		t.Fatalf("microserviceDesiredTargetCount() = %d, want 3", got)
	}

	if got := microserviceAppliedTargetCount([]dbaasv1.MicroserviceBalancingRuleAppliedRule{
		{Microservices: []string{"a", "b"}},
		{Microservices: []string{"c"}},
	}); got != 3 {
		t.Fatalf("microserviceAppliedTargetCount() = %d, want 3", got)
	}

	if got := permanentDesiredTargetCount([]dbaasv1.PermanentBalancingRuleItem{
		{Namespaces: []string{"ns-a", "ns-b"}},
		{Namespaces: []string{"ns-c"}},
	}); got != 3 {
		t.Fatalf("permanentDesiredTargetCount() = %d, want 3", got)
	}

	if got := permanentAppliedTargetCount([]dbaasv1.PermanentBalancingRuleAppliedRule{
		{Namespaces: []string{"ns-a", "ns-b"}},
		{Namespaces: []string{"ns-c"}},
	}); got != 3 {
		t.Fatalf("permanentAppliedTargetCount() = %d, want 3", got)
	}
}
