package controller

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

func TestMicroserviceRequestsFromSpec(t *testing.T) {
	got := microserviceRequestsFromSpec([]dbaasv1.DbMicroserviceBalancingRuleItem{
		{
			Type:          "mongodb",
			Label:         "tier=gold",
			Microservices: []string{"billing", "ledger"},
		},
		{
			Type:          "cassandra",
			Label:         "region=west",
			Microservices: []string{"audit"},
		},
	})

	if len(got) != 2 {
		t.Fatalf("expected 2 requests, got %d", len(got))
	}
	if got[0].Type != "mongodb" || got[0].Rules[0].Label != "tier=gold" {
		t.Fatalf("unexpected first request: %#v", got[0])
	}
	if got[0].Microservices[0] != "billing" || got[0].Microservices[1] != "ledger" {
		t.Fatalf("unexpected first request microservices: %#v", got[0].Microservices)
	}
	if got[1].Type != "cassandra" || got[1].Rules[0].Label != "region=west" {
		t.Fatalf("unexpected second request: %#v", got[1])
	}
}

func TestNamespaceRequestFromSpecItem(t *testing.T) {
	order := int64(7)

	got := namespaceRequestFromSpecItem(dbaasv1.DbNamespaceBalancingRuleItem{
		Type:               "mongodb",
		PhysicalDatabaseID: "mongodb-payments",
		Order:              order,
	})

	if got.Type != "mongodb" {
		t.Fatalf("expected type mongodb, got %q", got.Type)
	}
	if got.Order == nil || *got.Order != order {
		t.Fatalf("expected order %d, got %#v", order, got.Order)
	}
	if got.Rule.Type != "perNamespace" {
		t.Fatalf("expected perNamespace rule type, got %q", got.Rule.Type)
	}
	perNamespace, ok := got.Rule.Config["perNamespace"].(map[string]any)
	if !ok {
		t.Fatalf("expected perNamespace config, got %#v", got.Rule.Config)
	}
	if perNamespace["phydbid"] != "mongodb-payments" {
		t.Fatalf("expected phydbid mongodb-payments, got %#v", perNamespace["phydbid"])
	}
}

func TestPermanentRequestsFromSpec(t *testing.T) {
	got := permanentRequestsFromSpec([]dbaasv1.DbPermanentBalancingRuleItem{
		{
			DbType:             "mongodb",
			PhysicalDatabaseID: "mongodb-prod-a",
			Namespaces:         []string{"payments", "orders"},
		},
		{
			DbType:             "cassandra",
			PhysicalDatabaseID: "cassandra-prod-a",
			Namespaces:         []string{"audit"},
		},
	})

	if len(got) != 2 {
		t.Fatalf("expected 2 requests, got %d", len(got))
	}
	if got[0].DbType != "mongodb" || got[0].PhysicalDatabaseID != "mongodb-prod-a" {
		t.Fatalf("unexpected first request: %#v", got[0])
	}
	if got[0].Namespaces[0] != "payments" || got[0].Namespaces[1] != "orders" {
		t.Fatalf("unexpected first request namespaces: %#v", got[0].Namespaces)
	}
	if got[1].DbType != "cassandra" || got[1].PhysicalDatabaseID != "cassandra-prod-a" {
		t.Fatalf("unexpected second request: %#v", got[1])
	}
}

func TestCleanupMicroserviceTargetsSendsEmptyRulesForRemovedTargets(t *testing.T) {
	var got []aggregatorclient.OnMicroserviceRuleRequest
	var gotMethod, gotPath string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode request body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	reconciler := &BalancingRuleReconciler{
		Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
			return "token", nil
		}),
	}

	err := reconciler.cleanupMicroserviceTargets(context.Background(), "payments", "mongodb", []string{"billing", "ledger"})
	if err != nil {
		t.Fatalf("cleanupMicroserviceTargets returned error: %v", err)
	}

	if gotMethod != http.MethodPut {
		t.Fatalf("expected PUT, got %s", gotMethod)
	}
	if gotPath != "/api/v3/dbaas/payments/physical_databases/rules/onMicroservices" {
		t.Fatalf("unexpected path: %s", gotPath)
	}
	if len(got) != 1 {
		t.Fatalf("expected 1 cleanup request, got %d", len(got))
	}
	if got[0].Type != "mongodb" {
		t.Fatalf("expected type mongodb, got %q", got[0].Type)
	}
	if len(got[0].Rules) != 0 {
		t.Fatalf("expected empty rules cleanup payload, got %#v", got[0].Rules)
	}
	if got[0].Microservices[0] != "billing" || got[0].Microservices[1] != "ledger" {
		t.Fatalf("unexpected microservices: %#v", got[0].Microservices)
	}
}

func TestCleanupSupersededMicroserviceRulesOnlyRemovesMissingTargets(t *testing.T) {
	var got []aggregatorclient.OnMicroserviceRuleRequest
	var calls int

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode request body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	reconciler := &BalancingRuleReconciler{
		Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
			return "token", nil
		}),
	}
	rule := &dbaasv1.DbMicroserviceBalancingRule{}
	rule.Namespace = "payments"
	rule.Spec.Rules = []dbaasv1.DbMicroserviceBalancingRuleItem{
		{
			Type:          "mongodb",
			Label:         "tier=gold",
			Microservices: []string{"billing"},
		},
	}
	rule.Status.AppliedRules = []dbaasv1.DbMicroserviceBalancingRuleAppliedRule{
		{
			Type:          "mongodb",
			Microservices: []string{"billing", "ledger"},
		},
	}

	if err := reconciler.cleanupSupersededMicroserviceRules(context.Background(), rule); err != nil {
		t.Fatalf("cleanupSupersededMicroserviceRules returned error: %v", err)
	}

	if calls != 1 {
		t.Fatalf("expected 1 cleanup call, got %d", calls)
	}
	if len(got) != 1 || len(got[0].Microservices) != 1 || got[0].Microservices[0] != "ledger" {
		t.Fatalf("expected cleanup only for ledger, got %#v", got)
	}
}

func TestDeletePermanentTargetsSendsDeleteRequest(t *testing.T) {
	var got []aggregatorclient.PermanentBalancingRuleDeleteRequest
	var gotMethod, gotPath string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode request body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	reconciler := &BalancingRuleReconciler{
		Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
			return "token", nil
		}),
	}

	err := reconciler.deletePermanentTargets(context.Background(), "cassandra", []string{"blue", "green"})
	if err != nil {
		t.Fatalf("deletePermanentTargets returned error: %v", err)
	}

	if gotMethod != http.MethodDelete {
		t.Fatalf("expected DELETE, got %s", gotMethod)
	}
	if gotPath != "/api/v3/dbaas/balancing/rules/permanent" {
		t.Fatalf("unexpected path: %s", gotPath)
	}
	if len(got) != 1 {
		t.Fatalf("expected 1 delete request, got %d", len(got))
	}
	if got[0].DbType != "cassandra" {
		t.Fatalf("expected dbType cassandra, got %q", got[0].DbType)
	}
	if got[0].Namespaces[0] != "blue" || got[0].Namespaces[1] != "green" {
		t.Fatalf("unexpected namespaces: %#v", got[0].Namespaces)
	}
}

func TestCleanupSupersededPermanentRulesOnlyDeletesMissingTargets(t *testing.T) {
	var got []aggregatorclient.PermanentBalancingRuleDeleteRequest
	var calls int

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode request body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	reconciler := &BalancingRuleReconciler{
		Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
			return "token", nil
		}),
	}
	rule := &dbaasv1.DbPermanentBalancingRule{}
	rule.Spec.Rules = []dbaasv1.DbPermanentBalancingRuleItem{
		{
			DbType:             "cassandra",
			PhysicalDatabaseID: "cassandra-prod-a",
			Namespaces:         []string{"green"},
		},
	}
	rule.Status.AppliedRules = []dbaasv1.DbPermanentBalancingRuleAppliedRule{
		{
			DbType:     "cassandra",
			Namespaces: []string{"blue", "green"},
		},
	}

	if err := reconciler.cleanupSupersededPermanentRules(context.Background(), rule); err != nil {
		t.Fatalf("cleanupSupersededPermanentRules returned error: %v", err)
	}

	if calls != 1 {
		t.Fatalf("expected 1 cleanup call, got %d", calls)
	}
	if len(got) != 1 || len(got[0].Namespaces) != 1 || got[0].Namespaces[0] != "blue" {
		t.Fatalf("expected cleanup only for blue, got %#v", got)
	}
}
