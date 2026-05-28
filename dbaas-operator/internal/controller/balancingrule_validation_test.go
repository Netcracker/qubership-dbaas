package controller

import (
	"context"
	"strings"
	"testing"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

func TestValidateMicroserviceRuleSingleton(t *testing.T) {
	reconciler := &BalancingRuleReconciler{}
	valid := &dbaasv1.DbMicroserviceBalancingRule{
		Spec: dbaasv1.DbMicroserviceBalancingRuleSpec{
			Rules: []dbaasv1.DbMicroserviceBalancingRuleItem{
				{Type: "postgresql", Label: "zone=fast", Microservices: []string{"billing"}},
				{Type: "mongodb", Label: "tier=standard", Microservices: []string{"notifications"}},
			},
		},
	}
	valid.Name = dbaasv1.DbMicroserviceBalancingRuleName

	if reason, err := reconciler.validateMicroserviceRule(context.Background(), valid); err != nil || reason != "" {
		t.Fatalf("valid microservice rule returned reason=%q err=%v", reason, err)
	}

	invalidName := valid.DeepCopy()
	invalidName.Name = "other"
	if reason, err := reconciler.validateMicroserviceRule(context.Background(), invalidName); err != nil || !strings.Contains(reason, dbaasv1.DbMicroserviceBalancingRuleName) {
		t.Fatalf("invalid name reason=%q err=%v", reason, err)
	}

	duplicate := valid.DeepCopy()
	duplicate.Spec.Rules = append(duplicate.Spec.Rules, dbaasv1.DbMicroserviceBalancingRuleItem{
		Type:          "postgresql",
		Label:         "zone=slow",
		Microservices: []string{"billing"},
	})
	if reason, err := reconciler.validateMicroserviceRule(context.Background(), duplicate); err != nil || !strings.Contains(reason, "duplicate microservice") {
		t.Fatalf("duplicate microservice reason=%q err=%v", reason, err)
	}
}

func TestValidateNamespaceRuleSingleton(t *testing.T) {
	reconciler := &BalancingRuleReconciler{}
	valid := &dbaasv1.DbNamespaceBalancingRule{
		Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
			Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
				{Name: "pg-primary", Type: "postgresql", PhysicalDatabaseID: "postgresql-a", Order: 0},
				{Name: "pg-secondary", Type: "postgresql", PhysicalDatabaseID: "postgresql-b", Order: 1},
				{Name: "mongo-primary", Type: "mongodb", PhysicalDatabaseID: "mongodb-a", Order: 0},
			},
		},
	}
	valid.Name = dbaasv1.DbNamespaceBalancingRuleName

	if reason, err := reconciler.validateNamespaceRule(context.Background(), valid); err != nil || reason != "" {
		t.Fatalf("valid namespace rule returned reason=%q err=%v", reason, err)
	}

	duplicateName := valid.DeepCopy()
	duplicateName.Spec.Rules[1].Name = "pg-primary"
	if reason, err := reconciler.validateNamespaceRule(context.Background(), duplicateName); err != nil || !strings.Contains(reason, "duplicate name") {
		t.Fatalf("duplicate name reason=%q err=%v", reason, err)
	}

	duplicateOrder := valid.DeepCopy()
	duplicateOrder.Spec.Rules[1].Order = 0
	if reason, err := reconciler.validateNamespaceRule(context.Background(), duplicateOrder); err != nil || !strings.Contains(reason, "duplicate order") {
		t.Fatalf("duplicate order reason=%q err=%v", reason, err)
	}
}

func TestValidateNamespaceRuleGlobalAggregatorIdentity(t *testing.T) {
	scheme := runtime.NewScheme()
	if err := dbaasv1.AddToScheme(scheme); err != nil {
		t.Fatalf("add scheme: %v", err)
	}

	existing := &dbaasv1.DbNamespaceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:      dbaasv1.DbNamespaceBalancingRuleName,
			Namespace: "orders",
		},
		Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
			Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
				{Name: "orders-postgres-primary", Type: "postgresql", PhysicalDatabaseID: "postgresql-orders", Order: 10},
			},
		},
	}
	reconciler := &BalancingRuleReconciler{
		Client: fake.NewClientBuilder().WithScheme(scheme).WithObjects(existing).Build(),
	}

	duplicateName := &dbaasv1.DbNamespaceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:      dbaasv1.DbNamespaceBalancingRuleName,
			Namespace: "payments",
		},
		Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
			Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
				{Name: "orders-postgres-primary", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 11},
			},
		},
	}
	if reason, err := reconciler.validateNamespaceRule(context.Background(), duplicateName); err != nil || !strings.Contains(reason, "already managed") {
		t.Fatalf("duplicate global name reason=%q err=%v", reason, err)
	}

	duplicateOrder := duplicateName.DeepCopy()
	duplicateOrder.Spec.Rules[0].Name = "payments-postgres-primary"
	duplicateOrder.Spec.Rules[0].Type = "postgresql"
	duplicateOrder.Spec.Rules[0].Order = 10
	if reason, err := reconciler.validateNamespaceRule(context.Background(), duplicateOrder); err != nil || !strings.Contains(reason, "order 10") {
		t.Fatalf("duplicate global type/order reason=%q err=%v", reason, err)
	}
}

func TestValidatePermanentRuleSingleton(t *testing.T) {
	reconciler := &BalancingRuleReconciler{MyNamespace: "dbaas-system"}
	valid := &dbaasv1.DbPermanentBalancingRule{
		Spec: dbaasv1.DbPermanentBalancingRuleSpec{
			Rules: []dbaasv1.DbPermanentBalancingRuleItem{
				{DbType: "postgresql", PhysicalDatabaseID: "postgresql-a", Namespaces: []string{"payments", "orders"}},
				{DbType: "mongodb", PhysicalDatabaseID: "mongodb-a", Namespaces: []string{"notifications"}},
			},
		},
	}
	valid.Name = dbaasv1.DbPermanentBalancingRuleName
	valid.Namespace = "dbaas-system"

	if reason, err := reconciler.validatePermanentRule(context.Background(), valid); err != nil || reason != "" {
		t.Fatalf("valid permanent rule returned reason=%q err=%v", reason, err)
	}

	foreignNamespace := valid.DeepCopy()
	foreignNamespace.Namespace = "payments"
	if reason, err := reconciler.validatePermanentRule(context.Background(), foreignNamespace); err != nil || !strings.Contains(reason, "operator namespace") {
		t.Fatalf("foreign namespace reason=%q err=%v", reason, err)
	}

	duplicateTarget := valid.DeepCopy()
	duplicateTarget.Spec.Rules = append(duplicateTarget.Spec.Rules, dbaasv1.DbPermanentBalancingRuleItem{
		DbType:             "postgresql",
		PhysicalDatabaseID: "postgresql-b",
		Namespaces:         []string{"payments"},
	})
	if reason, err := reconciler.validatePermanentRule(context.Background(), duplicateTarget); err != nil || !strings.Contains(reason, "duplicate namespace") {
		t.Fatalf("duplicate target reason=%q err=%v", reason, err)
	}
}
