package controller

import (
	"testing"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
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
