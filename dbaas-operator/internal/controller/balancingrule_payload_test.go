package controller

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

var _ = Describe("BalancingRule payload helpers", func() {
	It("builds on-microservice requests from spec", func() {
		got := microserviceRequestsFromSpec([]dbaasv1.DbMicroserviceBalancingRuleItem{
			{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing", "ledger"}},
			{Type: "cassandra", Label: "region=west", Microservices: []string{"audit"}},
		})

		Expect(got).To(HaveLen(2))
		Expect(got[0].Type).To(Equal("mongodb"))
		Expect(got[0].Rules[0].Label).To(Equal("tier=gold"))
		Expect(got[0].Microservices).To(Equal([]string{"billing", "ledger"}))
		Expect(got[1].Type).To(Equal("cassandra"))
		Expect(got[1].Rules[0].Label).To(Equal("region=west"))
	})

	It("builds namespace rule requests from spec items", func() {
		order := int64(7)

		got := namespaceRequestFromSpecItem(dbaasv1.DbNamespaceBalancingRuleItem{
			Type:               "mongodb",
			PhysicalDatabaseID: "mongodb-payments",
			Order:              order,
		})

		Expect(got.Type).To(Equal("mongodb"))
		Expect(got.Order).NotTo(BeNil())
		Expect(*got.Order).To(Equal(order))
		Expect(got.Rule.Type).To(Equal("perNamespace"))
		Expect(got.Rule.Config).To(HaveKey("perNamespace"))
		perNamespace, ok := got.Rule.Config["perNamespace"].(map[string]any)
		Expect(ok).To(BeTrue())
		Expect(perNamespace["phydbid"]).To(Equal("mongodb-payments"))
	})

	It("builds permanent rule requests from spec", func() {
		got := permanentRequestsFromSpec([]dbaasv1.DbPermanentBalancingRuleItem{
			{DbType: "mongodb", PhysicalDatabaseID: "mongodb-prod-a", Namespaces: []string{"payments", "orders"}},
			{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"audit"}},
		})

		Expect(got).To(HaveLen(2))
		Expect(got[0].DbType).To(Equal("mongodb"))
		Expect(got[0].PhysicalDatabaseID).To(Equal("mongodb-prod-a"))
		Expect(got[0].Namespaces).To(Equal([]string{"payments", "orders"}))
		Expect(got[1].DbType).To(Equal("cassandra"))
		Expect(got[1].PhysicalDatabaseID).To(Equal("cassandra-prod-a"))
	})

	It("sends empty rules when cleaning removed microservice targets", func() {
		var got []aggregatorclient.OnMicroserviceRuleRequest
		var gotMethod, gotPath string
		var decodeErr error

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotMethod = r.Method
			gotPath = r.URL.Path
			decodeErr = json.NewDecoder(r.Body).Decode(&got)
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		reconciler := &BalancingRuleReconciler{
			Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
				return testToken, nil
			}),
		}

		Expect(reconciler.cleanupMicroserviceTargets(ctx, "payments", "mongodb", []string{"billing", "ledger"})).To(Succeed())

		Expect(decodeErr).NotTo(HaveOccurred())
		Expect(gotMethod).To(Equal(http.MethodPut))
		Expect(gotPath).To(Equal("/api/v3/dbaas/payments/physical_databases/rules/onMicroservices"))
		Expect(got).To(HaveLen(1))
		Expect(got[0].Type).To(Equal("mongodb"))
		Expect(got[0].Rules).To(BeEmpty())
		Expect(got[0].Microservices).To(Equal([]string{"billing", "ledger"}))
	})

	It("cleans only removed microservice targets before applying the new desired set", func() {
		var got []aggregatorclient.OnMicroserviceRuleRequest
		var calls int
		var decodeErr error

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			calls++
			decodeErr = json.NewDecoder(r.Body).Decode(&got)
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		reconciler := &BalancingRuleReconciler{
			Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
				return testToken, nil
			}),
		}
		rule := &dbaasv1.DbMicroserviceBalancingRule{}
		rule.Namespace = "payments"
		rule.Spec.Rules = []dbaasv1.DbMicroserviceBalancingRuleItem{
			{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing"}},
		}
		rule.Status.AppliedRules = []dbaasv1.DbMicroserviceBalancingRuleAppliedRule{
			{Type: "mongodb", Microservices: []string{"billing", "ledger"}},
		}

		Expect(reconciler.cleanupSupersededMicroserviceRules(ctx, rule)).To(Succeed())

		Expect(decodeErr).NotTo(HaveOccurred())
		Expect(calls).To(Equal(1))
		Expect(got).To(HaveLen(1))
		Expect(got[0].Microservices).To(Equal([]string{"ledger"}))
	})

	It("sends delete requests for namespace rule cleanup", func() {
		var gotMethod, gotPath string

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotMethod = r.Method
			gotPath = r.URL.Path
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		reconciler := &BalancingRuleReconciler{
			Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
				return testToken, nil
			}),
		}

		Expect(reconciler.deleteNamespaceRule(ctx, "payments", "payments-cassandra")).To(Succeed())

		Expect(gotMethod).To(Equal(http.MethodDelete))
		Expect(gotPath).To(Equal("/api/v3/dbaas/payments/physical_databases/balancing/rules/payments-cassandra"))
	})

	It("deletes only removed namespace rules before applying the new desired set", func() {
		var gotMethod, gotPath string
		var calls int

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			calls++
			gotMethod = r.Method
			gotPath = r.URL.Path
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		reconciler := &BalancingRuleReconciler{
			Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
				return testToken, nil
			}),
		}
		rule := &dbaasv1.DbNamespaceBalancingRule{}
		rule.Namespace = "payments"
		rule.Spec.Rules = []dbaasv1.DbNamespaceBalancingRuleItem{
			{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
		}
		rule.Status.AppliedRules = []dbaasv1.DbNamespaceBalancingRuleAppliedRule{
			{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
			{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
		}

		Expect(reconciler.cleanupSupersededNamespaceRules(ctx, rule)).To(Succeed())

		Expect(calls).To(Equal(1))
		Expect(gotMethod).To(Equal(http.MethodDelete))
		Expect(gotPath).To(Equal("/api/v3/dbaas/payments/physical_databases/balancing/rules/payments-cassandra"))
		// The deleted rule must be pruned from status so a later reconcile/delete
		// that iterates status.AppliedRules does not re-process or orphan it.
		Expect(rule.Status.AppliedRules).To(Equal([]dbaasv1.DbNamespaceBalancingRuleAppliedRule{
			{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
		}))
	})

	It("sends delete requests for permanent rule cleanup", func() {
		var got []aggregatorclient.PermanentBalancingRuleDeleteRequest
		var gotMethod, gotPath string
		var decodeErr error

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotMethod = r.Method
			gotPath = r.URL.Path
			decodeErr = json.NewDecoder(r.Body).Decode(&got)
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		reconciler := &BalancingRuleReconciler{
			Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
				return testToken, nil
			}),
		}

		Expect(reconciler.deletePermanentTargets(ctx, "cassandra", []string{"blue", "green"})).To(Succeed())

		Expect(decodeErr).NotTo(HaveOccurred())
		Expect(gotMethod).To(Equal(http.MethodDelete))
		Expect(gotPath).To(Equal("/api/v3/dbaas/balancing/rules/permanent"))
		Expect(got).To(HaveLen(1))
		Expect(got[0].DbType).To(Equal("cassandra"))
		Expect(got[0].Namespaces).To(Equal([]string{"blue", "green"}))
	})

	It("deletes only removed permanent targets before applying the new desired set", func() {
		var got []aggregatorclient.PermanentBalancingRuleDeleteRequest
		var calls int
		var decodeErr error

		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			calls++
			decodeErr = json.NewDecoder(r.Body).Decode(&got)
			w.WriteHeader(http.StatusOK)
		}))
		defer srv.Close()

		reconciler := &BalancingRuleReconciler{
			Aggregator: aggregatorclient.NewClientWithTokenFunc(srv.URL, func(context.Context) (string, error) {
				return testToken, nil
			}),
		}
		rule := &dbaasv1.DbPermanentBalancingRule{}
		rule.Spec.Rules = []dbaasv1.DbPermanentBalancingRuleItem{
			{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"green"}},
		}
		rule.Status.AppliedRules = []dbaasv1.DbPermanentBalancingRuleAppliedRule{
			{DbType: "cassandra", Namespaces: []string{"blue", "green"}},
		}

		Expect(reconciler.cleanupSupersededPermanentRules(ctx, rule)).To(Succeed())

		Expect(decodeErr).NotTo(HaveOccurred())
		Expect(calls).To(Equal(1))
		Expect(got).To(HaveLen(1))
		Expect(got[0].Namespaces).To(Equal([]string{"blue"}))
	})
})
