package controller

import (
	"context"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

var _ = Describe("BalancingRule validation", func() {
	const (
		businessNS = "default"
		operatorNS = "default"
	)

	AfterEach(func() {
		deleteIfExists(&dbaasv1.DbMicroserviceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbMicroserviceBalancingRuleName, Namespace: businessNS}})
		deleteIfExists(&dbaasv1.DbMicroserviceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: "other", Namespace: businessNS}})
		deleteIfExists(&dbaasv1.DbNamespaceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbNamespaceBalancingRuleName, Namespace: businessNS}})
		deleteIfExists(&dbaasv1.DbNamespaceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: "other-namespace-rules", Namespace: businessNS}})
		deleteIfExists(&dbaasv1.DbPermanentBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbPermanentBalancingRuleName, Namespace: operatorNS}})
	})

	Context("CRD admission schema", func() {
		It("rejects microservice rules with an empty rules list", func() {
			err := k8sClient.Create(ctx, unstructuredBalancingRule(
				"DbMicroserviceBalancingRule",
				dbaasv1.DbMicroserviceBalancingRuleName,
				businessNS,
				map[string]any{"rules": []any{}},
			))
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("rules"))
		})

		It("rejects microservice labels that do not match key=value", func() {
			err := k8sClient.Create(ctx, &dbaasv1.DbMicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbMicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.DbMicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.DbMicroserviceBalancingRuleItem{
						{Type: "mongodb", Label: "bad-label", Microservices: []string{"billing"}},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("label"))
		})

		It("rejects namespace rules when order is omitted", func() {
			err := k8sClient.Create(ctx, unstructuredBalancingRule(
				"DbNamespaceBalancingRule",
				dbaasv1.DbNamespaceBalancingRuleName,
				businessNS,
				map[string]any{
					"rules": []any{
						map[string]any{
							"name":               "payments-mongo",
							"type":               "mongodb",
							"physicalDatabaseId": "mongodb-payments",
						},
					},
				},
			))
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("order"))
		})

		It("rejects permanent rules with an empty namespace list", func() {
			err := k8sClient.Create(ctx, unstructuredBalancingRule(
				"DbPermanentBalancingRule",
				dbaasv1.DbPermanentBalancingRuleName,
				operatorNS,
				map[string]any{
					"rules": []any{
						map[string]any{
							"dbType":             "mongodb",
							"physicalDatabaseId": "mongodb-prod-a",
							"namespaces":         []any{},
						},
					},
				},
			))
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("namespaces"))
		})
	})

	Context("controller singleton validation", func() {
		It("accepts a valid microservice singleton and rejects duplicate type/microservice entries", func() {
			reconciler := &BalancingRuleReconciler{}
			valid := &dbaasv1.DbMicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbMicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.DbMicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.DbMicroserviceBalancingRuleItem{
						{Type: "postgresql", Label: "zone=fast", Microservices: []string{"billing"}},
						{Type: "mongodb", Label: "tier=standard", Microservices: []string{"notifications"}},
					},
				},
			}

			reason, err := reconciler.validateMicroserviceRule(valid)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(BeEmpty())

			invalidName := valid.DeepCopy()
			invalidName.Name = "other"
			reason, err = reconciler.validateMicroserviceRule(invalidName)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring(dbaasv1.DbMicroserviceBalancingRuleName))

			duplicate := valid.DeepCopy()
			duplicate.Spec.Rules = append(duplicate.Spec.Rules, dbaasv1.DbMicroserviceBalancingRuleItem{
				Type:          "postgresql",
				Label:         "zone=slow",
				Microservices: []string{"billing"},
			})
			reason, err = reconciler.validateMicroserviceRule(duplicate)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring("duplicate microservice"))
		})

		It("validates namespace rule list uniqueness locally", func() {
			reconciler := &BalancingRuleReconciler{}
			valid := &dbaasv1.DbNamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbNamespaceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
					Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
						{Name: "pg-primary", Type: "postgresql", PhysicalDatabaseID: "postgresql-a", Order: 0},
						{Name: "pg-secondary", Type: "postgresql", PhysicalDatabaseID: "postgresql-b", Order: 1},
						{Name: "mongo-primary", Type: "mongodb", PhysicalDatabaseID: "mongodb-a", Order: 0},
					},
				},
			}

			reason, err := reconciler.validateNamespaceRule(context.Background(), valid)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(BeEmpty())

			duplicateName := valid.DeepCopy()
			duplicateName.Spec.Rules[1].Name = "pg-primary"
			reason, err = reconciler.validateNamespaceRule(context.Background(), duplicateName)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring("duplicate name"))

			duplicateOrder := valid.DeepCopy()
			duplicateOrder.Spec.Rules[1].Order = 0
			reason, err = reconciler.validateNamespaceRule(context.Background(), duplicateOrder)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring("duplicate order"))
		})

		It("keeps global namespace-rule name checks best-effort and leaves type/order conflicts to aggregator 409", func() {
			existing := &dbaasv1.DbNamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: "other-namespace-rules", Namespace: businessNS},
				Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
					Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
						{Name: "orders-postgres-primary", Type: "postgresql", PhysicalDatabaseID: "postgresql-orders", Order: 10},
					},
				},
			}
			Expect(k8sClient.Create(ctx, existing)).To(Succeed())
			reconciler := &BalancingRuleReconciler{Client: k8sClient}

			duplicateName := &dbaasv1.DbNamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbNamespaceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.DbNamespaceBalancingRuleSpec{
					Rules: []dbaasv1.DbNamespaceBalancingRuleItem{
						{Name: "orders-postgres-primary", Type: "mongodb", PhysicalDatabaseID: "mongodb-payments", Order: 11},
					},
				},
			}
			reason, err := reconciler.validateNamespaceRule(context.Background(), duplicateName)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring("already managed"))

			duplicateOrder := duplicateName.DeepCopy()
			duplicateOrder.Spec.Rules[0].Name = "payments-postgres-primary"
			duplicateOrder.Spec.Rules[0].Type = "postgresql"
			duplicateOrder.Spec.Rules[0].Order = 10
			reason, err = reconciler.validateNamespaceRule(context.Background(), duplicateOrder)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(BeEmpty())
		})

		It("accepts a valid permanent singleton and rejects operator namespace/duplicate target violations", func() {
			reconciler := &BalancingRuleReconciler{MyNamespace: operatorNS}
			valid := &dbaasv1.DbPermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.DbPermanentBalancingRuleName, Namespace: operatorNS},
				Spec: dbaasv1.DbPermanentBalancingRuleSpec{
					Rules: []dbaasv1.DbPermanentBalancingRuleItem{
						{DbType: "postgresql", PhysicalDatabaseID: "postgresql-a", Namespaces: []string{"payments", "orders"}},
						{DbType: "mongodb", PhysicalDatabaseID: "mongodb-a", Namespaces: []string{"notifications"}},
					},
				},
			}

			reason, err := reconciler.validatePermanentRule(valid)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(BeEmpty())

			foreignNamespace := valid.DeepCopy()
			foreignNamespace.Namespace = "payments"
			reason, err = reconciler.validatePermanentRule(foreignNamespace)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring("operator namespace"))

			duplicateTarget := valid.DeepCopy()
			duplicateTarget.Spec.Rules = append(duplicateTarget.Spec.Rules, dbaasv1.DbPermanentBalancingRuleItem{
				DbType:             "postgresql",
				PhysicalDatabaseID: "postgresql-b",
				Namespaces:         []string{"payments"},
			})
			reason, err = reconciler.validatePermanentRule(duplicateTarget)
			Expect(err).NotTo(HaveOccurred())
			Expect(reason).To(ContainSubstring("duplicate namespace"))
		})
	})
})

func unstructuredBalancingRule(kind, name, namespace string, spec map[string]any) *unstructured.Unstructured {
	GinkgoHelper()
	return &unstructured.Unstructured{
		Object: map[string]any{
			"apiVersion": "dbaas.netcracker.com/v1",
			"kind":       kind,
			"metadata": map[string]any{
				"name":      name,
				"namespace": namespace,
			},
			"spec": spec,
		},
	}
}
