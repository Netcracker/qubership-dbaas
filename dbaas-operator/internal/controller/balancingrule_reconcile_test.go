package controller

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

type balancingRuleCall struct {
	method string
	path   string
	body   []byte
}

func TestReconcileMicroserviceAppliesRulesAndUpdatesStatus(t *testing.T) {
	rule := &dbaasv1.DbMicroserviceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:       dbaasv1.DbMicroserviceBalancingRuleName,
			Namespace:  "payments",
			Finalizers: []string{dbaasv1.DbMicroserviceBalancingRuleFinalizer},
		},
		Spec: dbaasv1.DbMicroserviceBalancingRuleSpec{
			Rules: []dbaasv1.DbMicroserviceBalancingRuleItem{
				{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing", "ledger"}},
				{Type: "cassandra", Label: "region=west", Microservices: []string{"audit"}},
			},
		},
	}
	reconciler, calls, closeServer := newBalancingRuleReconcilerFixture(t, "payments", rule)
	defer closeServer()

	if _, err := reconciler.ReconcileMicroservice(context.Background(), ctrl.Request{NamespacedName: client.ObjectKeyFromObject(rule)}); err != nil {
		t.Fatalf("ReconcileMicroservice returned error: %v", err)
	}

	if len(*calls) != 1 {
		t.Fatalf("expected 1 aggregator call, got %d", len(*calls))
	}
	if (*calls)[0].method != http.MethodPut || (*calls)[0].path != "/api/v3/dbaas/payments/physical_databases/rules/onMicroservices" {
		t.Fatalf("unexpected aggregator call: %#v", (*calls)[0])
	}
	var got []aggregatorclient.OnMicroserviceRuleRequest
	if err := json.Unmarshal((*calls)[0].body, &got); err != nil {
		t.Fatalf("decode aggregator body: %v", err)
	}
	if len(got) != 2 || got[0].Type != "mongodb" || got[1].Type != "cassandra" {
		t.Fatalf("unexpected aggregator body: %#v", got)
	}

	stored := &dbaasv1.DbMicroserviceBalancingRule{}
	if err := reconciler.Get(context.Background(), client.ObjectKeyFromObject(rule), stored); err != nil {
		t.Fatalf("get stored rule: %v", err)
	}
	if stored.Status.Phase != dbaasv1.PhaseSucceeded {
		t.Fatalf("expected succeeded phase, got %q", stored.Status.Phase)
	}
	if len(stored.Status.AppliedRules) != 2 {
		t.Fatalf("expected 2 applied rules, got %#v", stored.Status.AppliedRules)
	}
}

func TestReconcileMicroserviceCleansRemovedTargetsBeforeApply(t *testing.T) {
	rule := &dbaasv1.DbMicroserviceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:       dbaasv1.DbMicroserviceBalancingRuleName,
			Namespace:  "payments",
			Finalizers: []string{dbaasv1.DbMicroserviceBalancingRuleFinalizer},
		},
		Spec: dbaasv1.DbMicroserviceBalancingRuleSpec{
			Rules: []dbaasv1.DbMicroserviceBalancingRuleItem{
				{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing"}},
			},
		},
		Status: dbaasv1.DbMicroserviceBalancingRuleStatus{
			AppliedRules: []dbaasv1.DbMicroserviceBalancingRuleAppliedRule{
				{Type: "mongodb", Microservices: []string{"billing", "ledger"}},
			},
		},
	}
	reconciler, calls, closeServer := newBalancingRuleReconcilerFixture(t, "payments", rule)
	defer closeServer()

	if _, err := reconciler.ReconcileMicroservice(context.Background(), ctrl.Request{NamespacedName: client.ObjectKeyFromObject(rule)}); err != nil {
		t.Fatalf("ReconcileMicroservice returned error: %v", err)
	}

	if len(*calls) != 2 {
		t.Fatalf("expected cleanup then apply calls, got %d", len(*calls))
	}
	var cleanup []aggregatorclient.OnMicroserviceRuleRequest
	if err := json.Unmarshal((*calls)[0].body, &cleanup); err != nil {
		t.Fatalf("decode cleanup body: %v", err)
	}
	if len(cleanup) != 1 || len(cleanup[0].Rules) != 0 || len(cleanup[0].Microservices) != 1 || cleanup[0].Microservices[0] != "ledger" {
		t.Fatalf("unexpected cleanup body: %#v", cleanup)
	}
	var apply []aggregatorclient.OnMicroserviceRuleRequest
	if err := json.Unmarshal((*calls)[1].body, &apply); err != nil {
		t.Fatalf("decode apply body: %v", err)
	}
	if len(apply) != 1 || apply[0].Microservices[0] != "billing" {
		t.Fatalf("unexpected apply body: %#v", apply)
	}
}

func TestReconcileNamespaceAppliesEachRuleAndUpdatesStatus(t *testing.T) {
	rule := &dbaasv1.DbNamespaceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:      dbaasv1.DbNamespaceBalancingRuleName,
			Namespace: "payments",
		},
		Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
			Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
				{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
			},
		},
	}
	reconciler, calls, closeServer := newBalancingRuleReconcilerFixture(t, "payments", rule)
	defer closeServer()

	if _, err := reconciler.ReconcileNamespace(context.Background(), ctrl.Request{NamespacedName: client.ObjectKeyFromObject(rule)}); err != nil {
		t.Fatalf("ReconcileNamespace returned error: %v", err)
	}

	if len(*calls) != 2 {
		t.Fatalf("expected 2 aggregator calls, got %d", len(*calls))
	}
	if (*calls)[0].path != "/api/v3/dbaas/payments/physical_databases/balancing/rules/payments-mongo" {
		t.Fatalf("unexpected first path: %s", (*calls)[0].path)
	}
	if (*calls)[1].path != "/api/v3/dbaas/payments/physical_databases/balancing/rules/payments-cassandra" {
		t.Fatalf("unexpected second path: %s", (*calls)[1].path)
	}

	stored := &dbaasv1.DbNamespaceBalancingRule{}
	if err := reconciler.Get(context.Background(), client.ObjectKeyFromObject(rule), stored); err != nil {
		t.Fatalf("get stored rule: %v", err)
	}
	if stored.Status.Phase != dbaasv1.PhaseSucceeded {
		t.Fatalf("expected succeeded phase, got %q", stored.Status.Phase)
	}
	if len(stored.Status.AppliedRules) != 2 {
		t.Fatalf("expected 2 applied rules, got %#v", stored.Status.AppliedRules)
	}
}

func TestReconcilePermanentAppliesRulesAndUpdatesStatus(t *testing.T) {
	rule := &dbaasv1.DbPermanentBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:       dbaasv1.DbPermanentBalancingRuleName,
			Namespace:  "dbaas-system",
			Finalizers: []string{dbaasv1.DbPermanentBalancingRuleFinalizer},
		},
		Spec: dbaasv1.DbPermanentBalancingRuleSpec{
			Rules: []dbaasv1.DbPermanentBalancingRuleItem{
				{DbType: "mongodb", PhysicalDatabaseID: "mongodb-prod-a", Namespaces: []string{"payments", "orders"}},
				{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"audit"}},
			},
		},
	}
	reconciler, calls, closeServer := newBalancingRuleReconcilerFixture(t, "dbaas-system", rule)
	ownBusinessNamespaces(reconciler.Ownership, "payments", "orders", "audit")
	defer closeServer()

	if _, err := reconciler.ReconcilePermanent(context.Background(), ctrl.Request{NamespacedName: client.ObjectKeyFromObject(rule)}); err != nil {
		t.Fatalf("ReconcilePermanent returned error: %v", err)
	}

	if len(*calls) != 1 {
		t.Fatalf("expected 1 aggregator call, got %d", len(*calls))
	}
	if (*calls)[0].method != http.MethodPut || (*calls)[0].path != "/api/v3/dbaas/balancing/rules/permanent" {
		t.Fatalf("unexpected aggregator call: %#v", (*calls)[0])
	}
	var got []aggregatorclient.PermanentBalancingRuleRequest
	if err := json.Unmarshal((*calls)[0].body, &got); err != nil {
		t.Fatalf("decode aggregator body: %v", err)
	}
	if len(got) != 2 || got[0].DbType != "mongodb" || got[1].DbType != "cassandra" {
		t.Fatalf("unexpected aggregator body: %#v", got)
	}

	stored := &dbaasv1.DbPermanentBalancingRule{}
	if err := reconciler.Get(context.Background(), client.ObjectKeyFromObject(rule), stored); err != nil {
		t.Fatalf("get stored rule: %v", err)
	}
	if stored.Status.Phase != dbaasv1.PhaseSucceeded {
		t.Fatalf("expected succeeded phase, got %q", stored.Status.Phase)
	}
	if len(stored.Status.AppliedRules) != 2 {
		t.Fatalf("expected 2 applied rules, got %#v", stored.Status.AppliedRules)
	}
}

func TestReconcilePermanentCleansRemovedTargetsBeforeApply(t *testing.T) {
	rule := &dbaasv1.DbPermanentBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:       dbaasv1.DbPermanentBalancingRuleName,
			Namespace:  "dbaas-system",
			Finalizers: []string{dbaasv1.DbPermanentBalancingRuleFinalizer},
		},
		Spec: dbaasv1.DbPermanentBalancingRuleSpec{
			Rules: []dbaasv1.DbPermanentBalancingRuleItem{
				{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"green"}},
			},
		},
		Status: dbaasv1.DbPermanentBalancingRuleStatus{
			AppliedRules: []dbaasv1.DbPermanentBalancingRuleAppliedRule{
				{DbType: "cassandra", Namespaces: []string{"blue", "green"}},
			},
		},
	}
	reconciler, calls, closeServer := newBalancingRuleReconcilerFixture(t, "dbaas-system", rule)
	ownBusinessNamespaces(reconciler.Ownership, "green")
	defer closeServer()

	if _, err := reconciler.ReconcilePermanent(context.Background(), ctrl.Request{NamespacedName: client.ObjectKeyFromObject(rule)}); err != nil {
		t.Fatalf("ReconcilePermanent returned error: %v", err)
	}

	if len(*calls) != 2 {
		t.Fatalf("expected delete then apply calls, got %d", len(*calls))
	}
	if (*calls)[0].method != http.MethodDelete {
		t.Fatalf("expected first call DELETE, got %s", (*calls)[0].method)
	}
	var cleanup []aggregatorclient.PermanentBalancingRuleDeleteRequest
	if err := json.Unmarshal((*calls)[0].body, &cleanup); err != nil {
		t.Fatalf("decode cleanup body: %v", err)
	}
	if len(cleanup) != 1 || len(cleanup[0].Namespaces) != 1 || cleanup[0].Namespaces[0] != "blue" {
		t.Fatalf("unexpected cleanup body: %#v", cleanup)
	}
	if (*calls)[1].method != http.MethodPut {
		t.Fatalf("expected second call PUT, got %s", (*calls)[1].method)
	}
}

func TestReconcileMicroserviceAddsFinalizerBeforeApplying(t *testing.T) {
	rule := &dbaasv1.DbMicroserviceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:      dbaasv1.DbMicroserviceBalancingRuleName,
			Namespace: "payments",
		},
		Spec: dbaasv1.DbMicroserviceBalancingRuleSpec{
			Rules: []dbaasv1.DbMicroserviceBalancingRuleItem{
				{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing"}},
			},
		},
	}
	reconciler, calls, closeServer := newBalancingRuleReconcilerFixture(t, "payments", rule)
	defer closeServer()

	if _, err := reconciler.ReconcileMicroservice(context.Background(), ctrl.Request{NamespacedName: client.ObjectKeyFromObject(rule)}); err != nil {
		t.Fatalf("ReconcileMicroservice returned error: %v", err)
	}

	if len(*calls) != 0 {
		t.Fatalf("expected no aggregator calls while adding finalizer, got %d", len(*calls))
	}
	stored := &dbaasv1.DbMicroserviceBalancingRule{}
	if err := reconciler.Get(context.Background(), client.ObjectKeyFromObject(rule), stored); err != nil {
		t.Fatalf("get stored rule: %v", err)
	}
	if !containsString(stored.Finalizers, dbaasv1.DbMicroserviceBalancingRuleFinalizer) {
		t.Fatalf("expected finalizer to be added, got %#v", stored.Finalizers)
	}
}

func newBalancingRuleReconcilerFixture(
	t *testing.T,
	ownedNamespace string,
	objs ...client.Object,
) (*BalancingRuleReconciler, *[]balancingRuleCall, func()) {
	t.Helper()

	calls := []balancingRuleCall{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Fatalf("read request body: %v", err)
		}
		calls = append(calls, balancingRuleCall{
			method: r.Method,
			path:   r.URL.Path,
			body:   body,
		})
		w.WriteHeader(http.StatusOK)
	}))

	scheme := runtime.NewScheme()
	if err := dbaasv1.AddToScheme(scheme); err != nil {
		t.Fatalf("add dbaas scheme: %v", err)
	}
	k8sClient := fake.NewClientBuilder().
		WithScheme(scheme).
		WithObjects(objs...).
		WithStatusSubresource(
			&dbaasv1.DbMicroserviceBalancingRule{},
			&dbaasv1.DbNamespaceBalancingRule{},
			&dbaasv1.DbPermanentBalancingRule{},
		).
		Build()
	resolver := ownership.NewOwnershipResolver(ownedNamespace, k8sClient)
	resolver.SetOwner(ownedNamespace, ownedNamespace)

	return &BalancingRuleReconciler{
			Client:      k8sClient,
			Scheme:      scheme,
			Aggregator:  aggregatorclient.NewClientWithTokenFunc(server.URL, func(context.Context) (string, error) { return "token", nil }),
			Recorder:    record.NewFakeRecorder(32),
			Ownership:   resolver,
			MyNamespace: ownedNamespace,
		}, &calls, func() {
			server.Close()
		}
}

func ownBusinessNamespaces(resolver *ownership.OwnershipResolver, namespaces ...string) {
	for _, namespace := range namespaces {
		resolver.SetOwner(namespace, "dbaas-system")
	}
}
