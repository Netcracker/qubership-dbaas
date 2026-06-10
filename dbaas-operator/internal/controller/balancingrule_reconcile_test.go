package controller

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

type balancingRuleCall struct {
	method string
	path   string
	body   []byte
}

type balancingRuleReconcileFixture struct {
	reconciler *BalancingRuleReconciler
	calls      []balancingRuleCall
	statusCode int
	statuses   []int
	server     *httptest.Server
	recorder   *record.FakeRecorder
}

var _ = Describe("BalancingRule Controller", func() {
	const (
		businessNS = "default"
		operatorNS = "default"
	)

	var fixture *balancingRuleReconcileFixture

	BeforeEach(func() {
		fixture = newBalancingRuleReconcileFixture(businessNS)
	})

	AfterEach(func() {
		fixture.close()
		deleteIfExists(&dbaasv1.MicroserviceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS}})
		deleteIfExists(&dbaasv1.NamespaceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: businessNS}})
		deleteIfExists(&dbaasv1.PermanentBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.PermanentBalancingRuleName, Namespace: operatorNS}})
	})

	Context("microservice singleton", func() {
		It("applies the full rule list and updates status", func() {
			rule := &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.MicroserviceBalancingRuleName,
					Namespace:  businessNS,
					Finalizers: []string{dbaasv1.MicroserviceBalancingRuleFinalizer},
				},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing", "ledger"}},
						{Type: "cassandra", Label: "region=west", Microservices: []string{"audit"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())

			stored, result, err := reconcileMicroserviceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.calls).To(HaveLen(1))
			Expect(fixture.calls[0].method).To(Equal(http.MethodPut))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/rules/onMicroservices"))

			var got []aggregatorclient.OnMicroserviceRuleRequest
			Expect(json.Unmarshal(fixture.calls[0].body, &got)).To(Succeed())
			Expect(got).To(HaveLen(2))
			Expect(got[0].Type).To(Equal("mongodb"))
			Expect(got[1].Type).To(Equal("cassandra"))
			Expect(stored.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(stored.Status.AppliedRules).To(HaveLen(2))
		})

		It("cleans removed targets before applying the new desired list", func() {
			rule := &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.MicroserviceBalancingRuleName,
					Namespace:  businessNS,
					Finalizers: []string{dbaasv1.MicroserviceBalancingRuleFinalizer},
				},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateMicroserviceStatus(rule, []dbaasv1.MicroserviceBalancingRuleAppliedRule{
				{Type: "mongodb", Microservices: []string{"billing", "ledger"}},
			})

			_, _, err := reconcileMicroserviceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.calls).To(HaveLen(2))
			var cleanup []aggregatorclient.OnMicroserviceRuleRequest
			Expect(json.Unmarshal(fixture.calls[0].body, &cleanup)).To(Succeed())
			Expect(cleanup).To(HaveLen(1))
			Expect(cleanup[0].Rules).To(BeEmpty())
			Expect(cleanup[0].Microservices).To(Equal([]string{"ledger"}))
			var apply []aggregatorclient.OnMicroserviceRuleRequest
			Expect(json.Unmarshal(fixture.calls[1].body, &apply)).To(Succeed())
			Expect(apply).To(HaveLen(1))
			Expect(apply[0].Microservices).To(Equal([]string{"billing"}))
		})

		It("adds the finalizer before applying rules", func() {
			rule := &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())

			stored, _, err := reconcileMicroserviceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.calls).To(BeEmpty())
			Expect(slices.Contains(stored.Finalizers, dbaasv1.MicroserviceBalancingRuleFinalizer)).To(BeTrue())
		})

		It("cleans applied rules and removes the finalizer when deleted", func() {
			rule := &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.MicroserviceBalancingRuleName,
					Namespace:  businessNS,
					Finalizers: []string{dbaasv1.MicroserviceBalancingRuleFinalizer},
				},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "mongodb", Label: "tier=gold", Microservices: []string{"billing", "ledger"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateMicroserviceStatus(rule, []dbaasv1.MicroserviceBalancingRuleAppliedRule{
				{Type: "mongodb", Microservices: []string{"billing", "ledger"}},
			})
			Expect(k8sClient.Delete(ctx, rule)).To(Succeed())

			result, err := fixture.reconciler.ReconcileMicroservice(ctx, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(rule)})

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.calls).To(HaveLen(1))
			Expect(fixture.calls[0].method).To(Equal(http.MethodPut))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/rules/onMicroservices"))
			var cleanup []aggregatorclient.OnMicroserviceRuleRequest
			Expect(json.Unmarshal(fixture.calls[0].body, &cleanup)).To(Succeed())
			Expect(cleanup).To(HaveLen(1))
			Expect(cleanup[0].Type).To(Equal("mongodb"))
			Expect(cleanup[0].Rules).To(BeEmpty())
			Expect(cleanup[0].Microservices).To(Equal([]string{"billing", "ledger"}))
			Eventually(func() bool {
				current := &dbaasv1.MicroserviceBalancingRule{}
				err := k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), current)
				return apierrors.IsNotFound(err)
			}).Should(BeTrue())
		})
	})

	Context("namespace singleton", func() {
		It("applies each namespace rule and updates status", func() {
			rule := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.NamespaceBalancingRuleName,
					Namespace:  businessNS,
					Finalizers: []string{dbaasv1.NamespaceBalancingRuleFinalizer},
				},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
						{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
						{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())

			stored, result, err := reconcileNamespaceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.calls).To(HaveLen(2))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-mongo"))
			Expect(fixture.calls[1].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-cassandra"))
			Expect(stored.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(stored.Status.AppliedRules).To(HaveLen(2))
		})

		It("records successfully applied namespace rules when a later apply fails", func() {
			rule := namespaceRuleWithFinalizer(businessNS)
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			fixture.statuses = []int{http.StatusOK, http.StatusConflict}

			stored, _, err := reconcileNamespaceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.calls).To(HaveLen(2))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-mongo"))
			Expect(fixture.calls[1].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-cassandra"))
			Expect(stored.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			Expect(stored.Status.AppliedRules).To(Equal([]dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
			}))
		})

		It("keeps previously-applied rules recorded when an updated spec fails to apply a new earlier rule", func() {
			// D1 follow-up: status already records two rules that are live in the
			// aggregator. The updated spec prepends a new rule whose apply fails, so
			// the loop never re-applies the existing two. They must stay recorded —
			// emptying status here would orphan rules still live aggregator-side.
			rule := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.NamespaceBalancingRuleName,
					Namespace:  businessNS,
					Finalizers: []string{dbaasv1.NamespaceBalancingRuleFinalizer},
				},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
						{Name: "payments-redis", Type: "redis", PhysicalDatabaseID: "redis-payments", Order: 5},
						{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
						{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateNamespaceStatus(rule, []dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
				{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
			})
			fixture.statuses = []int{http.StatusConflict}

			stored, _, err := reconcileNamespaceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			// No cleanup deletes (every recorded rule is still desired); only the
			// first apply (payments-redis) is attempted, and it fails.
			Expect(fixture.calls).To(HaveLen(1))
			Expect(fixture.calls[0].method).To(Equal(http.MethodPut))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-redis"))
			Expect(stored.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			Expect(stored.Status.AppliedRules).To(Equal([]dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
				{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
			}))
		})

		It("deletes removed rules before applying the new desired list", func() {
			rule := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.NamespaceBalancingRuleName,
					Namespace:  businessNS,
					Finalizers: []string{dbaasv1.NamespaceBalancingRuleFinalizer},
				},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
						{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateNamespaceStatus(rule, []dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
				{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
			})

			_, _, err := reconcileNamespaceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.calls).To(HaveLen(2))
			Expect(fixture.calls[0].method).To(Equal(http.MethodDelete))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-cassandra"))
			Expect(fixture.calls[1].method).To(Equal(http.MethodPut))
			Expect(fixture.calls[1].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-mongo"))
		})

		It("adds the finalizer before applying rules", func() {
			rule := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
						{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())

			stored, _, err := reconcileNamespaceAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.calls).To(BeEmpty())
			Expect(slices.Contains(stored.Finalizers, dbaasv1.NamespaceBalancingRuleFinalizer)).To(BeTrue())
		})

		It("cleans applied rules and removes the finalizer when deleted", func() {
			rule := namespaceRuleWithFinalizer(businessNS)
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateNamespaceStatus(rule, []dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
				{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
			})
			Expect(k8sClient.Delete(ctx, rule)).To(Succeed())

			result, err := fixture.reconciler.ReconcileNamespace(ctx, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(rule)})

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.calls).To(HaveLen(2))
			Expect(fixture.calls[0].method).To(Equal(http.MethodDelete))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-mongo"))
			Expect(fixture.calls[1].method).To(Equal(http.MethodDelete))
			Expect(fixture.calls[1].path).To(Equal("/api/v3/dbaas/default/physical_databases/balancing/rules/payments-cassandra"))
			Eventually(func() bool {
				current := &dbaasv1.NamespaceBalancingRule{}
				err := k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), current)
				return apierrors.IsNotFound(err)
			}).Should(BeTrue())
		})

		It("keeps the finalizer when cleanup fails", func() {
			rule := namespaceRuleWithFinalizer(businessNS)
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateNamespaceStatus(rule, []dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
			})
			Expect(k8sClient.Delete(ctx, rule)).To(Succeed())
			fixture.statusCode = http.StatusInternalServerError

			_, err := fixture.reconciler.ReconcileNamespace(ctx, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(rule)})

			Expect(err).To(HaveOccurred())
			current := &dbaasv1.NamespaceBalancingRule{}
			Expect(k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), current)).To(Succeed())
			Expect(slices.Contains(current.Finalizers, dbaasv1.NamespaceBalancingRuleFinalizer)).To(BeTrue())
		})

		It("removes the finalizer when cleanup retry receives not found", func() {
			rule := namespaceRuleWithFinalizer(businessNS)
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updateNamespaceStatus(rule, []dbaasv1.NamespaceBalancingRuleAppliedRule{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
			})
			Expect(k8sClient.Delete(ctx, rule)).To(Succeed())
			fixture.statusCode = http.StatusNotFound

			_, err := fixture.reconciler.ReconcileNamespace(ctx, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(rule)})

			Expect(err).NotTo(HaveOccurred())
			Eventually(func() bool {
				current := &dbaasv1.NamespaceBalancingRule{}
				err := k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), current)
				return apierrors.IsNotFound(err)
			}).Should(BeTrue())
		})
	})

	Context("permanent singleton", func() {
		It("applies the full permanent rule list and updates status", func() {
			fixture.reconciler.Ownership.SetOwner("payments", operatorNS)
			fixture.reconciler.Ownership.SetOwner("orders", operatorNS)
			fixture.reconciler.Ownership.SetOwner("audit", operatorNS)
			rule := &dbaasv1.PermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.PermanentBalancingRuleName,
					Namespace:  operatorNS,
					Finalizers: []string{dbaasv1.PermanentBalancingRuleFinalizer},
				},
				Spec: dbaasv1.PermanentBalancingRuleSpec{
					Rules: []dbaasv1.PermanentBalancingRuleItem{
						{DbType: "mongodb", PhysicalDatabaseID: "mongodb-prod-a", Namespaces: []string{"payments", "orders"}},
						{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"audit"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())

			stored, result, err := reconcilePermanentAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.calls).To(HaveLen(1))
			Expect(fixture.calls[0].method).To(Equal(http.MethodPut))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/balancing/rules/permanent"))
			var got []aggregatorclient.PermanentBalancingRuleRequest
			Expect(json.Unmarshal(fixture.calls[0].body, &got)).To(Succeed())
			Expect(got).To(HaveLen(2))
			Expect(got[0].DbType).To(Equal("mongodb"))
			Expect(got[1].DbType).To(Equal("cassandra"))
			Expect(stored.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(stored.Status.AppliedRules).To(HaveLen(2))
		})

		It("deletes removed targets before applying the new desired list", func() {
			fixture.reconciler.Ownership.SetOwner("green", operatorNS)
			rule := &dbaasv1.PermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.PermanentBalancingRuleName,
					Namespace:  operatorNS,
					Finalizers: []string{dbaasv1.PermanentBalancingRuleFinalizer},
				},
				Spec: dbaasv1.PermanentBalancingRuleSpec{
					Rules: []dbaasv1.PermanentBalancingRuleItem{
						{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"green"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updatePermanentStatus(rule, []dbaasv1.PermanentBalancingRuleAppliedRule{
				{DbType: "cassandra", Namespaces: []string{"blue", "green"}},
			})

			_, _, err := reconcilePermanentAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.calls).To(HaveLen(2))
			Expect(fixture.calls[0].method).To(Equal(http.MethodDelete))
			var cleanup []aggregatorclient.PermanentBalancingRuleDeleteRequest
			Expect(json.Unmarshal(fixture.calls[0].body, &cleanup)).To(Succeed())
			Expect(cleanup).To(HaveLen(1))
			Expect(cleanup[0].Namespaces).To(Equal([]string{"blue"}))
			Expect(fixture.calls[1].method).To(Equal(http.MethodPut))
		})

		It("waits for dependency when a target namespace is not owned yet", func() {
			rule := &dbaasv1.PermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.PermanentBalancingRuleName,
					Namespace:  operatorNS,
					Finalizers: []string{dbaasv1.PermanentBalancingRuleFinalizer},
				},
				Spec: dbaasv1.PermanentBalancingRuleSpec{
					Rules: []dbaasv1.PermanentBalancingRuleItem{
						{DbType: "mongodb", PhysicalDatabaseID: "mongodb-prod-a", Namespaces: []string{"payments"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())

			stored, result, err := reconcilePermanentAndFetch(fixture.reconciler, client.ObjectKeyFromObject(rule))

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).NotTo(BeZero())
			Expect(fixture.calls).To(BeEmpty())
			Expect(stored.Status.Phase).To(Equal(dbaasv1.PhaseWaitingForDependency))
			ready := findCondition(stored.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonWaitingForNamespaceBinding))
			stalled := findCondition(stored.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
		})

		It("deletes applied targets and removes the finalizer when deleted", func() {
			rule := &dbaasv1.PermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{
					Name:       dbaasv1.PermanentBalancingRuleName,
					Namespace:  operatorNS,
					Finalizers: []string{dbaasv1.PermanentBalancingRuleFinalizer},
				},
				Spec: dbaasv1.PermanentBalancingRuleSpec{
					Rules: []dbaasv1.PermanentBalancingRuleItem{
						{DbType: "cassandra", PhysicalDatabaseID: "cassandra-prod-a", Namespaces: []string{"blue", "green"}},
					},
				},
			}
			Expect(k8sClient.Create(ctx, rule)).To(Succeed())
			updatePermanentStatus(rule, []dbaasv1.PermanentBalancingRuleAppliedRule{
				{DbType: "cassandra", Namespaces: []string{"blue", "green"}},
			})
			Expect(k8sClient.Delete(ctx, rule)).To(Succeed())

			result, err := fixture.reconciler.ReconcilePermanent(ctx, reconcile.Request{NamespacedName: client.ObjectKeyFromObject(rule)})

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.calls).To(HaveLen(1))
			Expect(fixture.calls[0].method).To(Equal(http.MethodDelete))
			Expect(fixture.calls[0].path).To(Equal("/api/v3/dbaas/balancing/rules/permanent"))
			var cleanup []aggregatorclient.PermanentBalancingRuleDeleteRequest
			Expect(json.Unmarshal(fixture.calls[0].body, &cleanup)).To(Succeed())
			Expect(cleanup).To(HaveLen(1))
			Expect(cleanup[0].DbType).To(Equal("cassandra"))
			Expect(cleanup[0].Namespaces).To(Equal([]string{"blue", "green"}))
			Eventually(func() bool {
				current := &dbaasv1.PermanentBalancingRule{}
				err := k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), current)
				return apierrors.IsNotFound(err)
			}).Should(BeTrue())
		})
	})
})

func newBalancingRuleReconcileFixture(ownedNamespace string) *balancingRuleReconcileFixture {
	GinkgoHelper()
	fixture := &balancingRuleReconcileFixture{
		recorder:   record.NewFakeRecorder(32),
		statusCode: http.StatusOK,
	}
	fixture.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		fixture.calls = append(fixture.calls, balancingRuleCall{
			method: r.Method,
			path:   r.URL.Path,
			body:   body,
		})
		statusCode := fixture.statusCode
		if len(fixture.statuses) > 0 {
			statusCode = fixture.statuses[0]
			fixture.statuses = fixture.statuses[1:]
		}
		w.WriteHeader(statusCode)
	}))
	resolver := ownership.NewOwnershipResolver(ownedNamespace, k8sClient)
	resolver.SetOwner(ownedNamespace, ownedNamespace)
	fixture.reconciler = &BalancingRuleReconciler{
		Client:      k8sClient,
		Scheme:      k8sClient.Scheme(),
		Aggregator:  aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(context.Context) (string, error) { return testToken, nil }),
		Recorder:    fixture.recorder,
		Ownership:   resolver,
		MyNamespace: ownedNamespace,
	}
	return fixture
}

func namespaceRuleWithFinalizer(namespace string) *dbaasv1.NamespaceBalancingRule {
	return &dbaasv1.NamespaceBalancingRule{
		ObjectMeta: metav1.ObjectMeta{
			Name:       dbaasv1.NamespaceBalancingRuleName,
			Namespace:  namespace,
			Finalizers: []string{dbaasv1.NamespaceBalancingRuleFinalizer},
		},
		Spec: dbaasv1.NamespaceBalancingRuleSpec{
			Rules: []dbaasv1.NamespaceBalancingRuleItem{
				{Name: "payments-mongo", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 10},
				{Name: "payments-cassandra", Type: "cassandra", PhysicalDatabaseID: "cassandra-payments", Order: 20},
			},
		},
	}
}

func (f *balancingRuleReconcileFixture) close() {
	if f.server != nil {
		f.server.Close()
	}
	if f.recorder != nil {
		drainRecordedEvents(f.recorder.Events)
	}
}

func reconcileMicroserviceAndFetch(
	reconciler *BalancingRuleReconciler,
	key types.NamespacedName,
) (*dbaasv1.MicroserviceBalancingRule, reconcile.Result, error) {
	GinkgoHelper()
	Eventually(func() error {
		return k8sClient.Get(ctx, key, &dbaasv1.MicroserviceBalancingRule{})
	}).Should(Succeed())
	result, err := reconciler.ReconcileMicroservice(ctx, reconcile.Request{NamespacedName: key})
	obj := &dbaasv1.MicroserviceBalancingRule{}
	Expect(k8sClient.Get(ctx, key, obj)).To(Succeed())
	return obj, result, err
}

func reconcileNamespaceAndFetch(
	reconciler *BalancingRuleReconciler,
	key types.NamespacedName,
) (*dbaasv1.NamespaceBalancingRule, reconcile.Result, error) {
	GinkgoHelper()
	Eventually(func() error {
		return k8sClient.Get(ctx, key, &dbaasv1.NamespaceBalancingRule{})
	}).Should(Succeed())
	result, err := reconciler.ReconcileNamespace(ctx, reconcile.Request{NamespacedName: key})
	obj := &dbaasv1.NamespaceBalancingRule{}
	Expect(k8sClient.Get(ctx, key, obj)).To(Succeed())
	return obj, result, err
}

func reconcilePermanentAndFetch(
	reconciler *BalancingRuleReconciler,
	key types.NamespacedName,
) (*dbaasv1.PermanentBalancingRule, reconcile.Result, error) {
	GinkgoHelper()
	Eventually(func() error {
		return k8sClient.Get(ctx, key, &dbaasv1.PermanentBalancingRule{})
	}).Should(Succeed())
	result, err := reconciler.ReconcilePermanent(ctx, reconcile.Request{NamespacedName: key})
	obj := &dbaasv1.PermanentBalancingRule{}
	Expect(k8sClient.Get(ctx, key, obj)).To(Succeed())
	return obj, result, err
}

func updateMicroserviceStatus(rule *dbaasv1.MicroserviceBalancingRule, applied []dbaasv1.MicroserviceBalancingRuleAppliedRule) {
	GinkgoHelper()
	stored := &dbaasv1.MicroserviceBalancingRule{}
	Expect(k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), stored)).To(Succeed())
	stored.Status.AppliedRules = applied
	Expect(k8sClient.Status().Update(ctx, stored)).To(Succeed())
}

func updateNamespaceStatus(rule *dbaasv1.NamespaceBalancingRule, applied []dbaasv1.NamespaceBalancingRuleAppliedRule) {
	GinkgoHelper()
	stored := &dbaasv1.NamespaceBalancingRule{}
	Expect(k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), stored)).To(Succeed())
	stored.Status.AppliedRules = applied
	Expect(k8sClient.Status().Update(ctx, stored)).To(Succeed())
}

func updatePermanentStatus(rule *dbaasv1.PermanentBalancingRule, applied []dbaasv1.PermanentBalancingRuleAppliedRule) {
	GinkgoHelper()
	stored := &dbaasv1.PermanentBalancingRule{}
	Expect(k8sClient.Get(ctx, client.ObjectKeyFromObject(rule), stored)).To(Succeed())
	stored.Status.AppliedRules = applied
	Expect(k8sClient.Status().Update(ctx, stored)).To(Succeed())
}
