package controller

import (
	"context"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

// conflictNS hosts the second NamespaceBalancingRule singleton used by the
// cross-namespace conflict spec. Aggregator rule names are global, so the
// collision that validateNamespaceRuleGlobalConflicts guards against is between
// the singletons of two different namespaces.
const conflictNS = "balancing-conflict-ns"

var _ = Describe("BalancingRule validation", func() {
	const (
		businessNS = "default"
		operatorNS = "default"
	)

	AfterEach(func() {
		deleteIfExists(&dbaasv1.MicroserviceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS}})
		deleteIfExists(&dbaasv1.MicroserviceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: "other", Namespace: businessNS}})
		deleteIfExists(&dbaasv1.NamespaceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: businessNS}})
		deleteIfExists(&dbaasv1.NamespaceBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: conflictNS}})
		deleteIfExists(&dbaasv1.PermanentBalancingRule{ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.PermanentBalancingRuleName, Namespace: operatorNS}})
	})

	Context("CRD admission schema", func() {
		It("rejects microservice rules with an empty rules list", func() {
			err := k8sClient.Create(ctx, unstructuredBalancingRule(
				"MicroserviceBalancingRule",
				dbaasv1.MicroserviceBalancingRuleName,
				businessNS,
				map[string]any{"rules": []any{}},
			))
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("rules"))
		})

		It("rejects microservice labels that do not match key=value", func() {
			err := k8sClient.Create(ctx, &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "mongodb", Label: "bad-label", Microservices: []string{"billing"}},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("label"))
		})

		It("rejects namespace rules when order is omitted", func() {
			err := k8sClient.Create(ctx, unstructuredBalancingRule(
				"NamespaceBalancingRule",
				dbaasv1.NamespaceBalancingRuleName,
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
				"PermanentBalancingRule",
				dbaasv1.PermanentBalancingRuleName,
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
			valid := &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "postgresql", Label: "zone=fast", Microservices: []string{"billing"}},
						{Type: "mongodb", Label: "tier=standard", Microservices: []string{"notifications"}},
					},
				},
			}

			reason := reconciler.validateMicroserviceRule(valid)
			Expect(reason).To(BeEmpty())

			invalidName := valid.DeepCopy()
			invalidName.Name = "other"
			reason = reconciler.validateMicroserviceRule(invalidName)
			Expect(reason).To(ContainSubstring(dbaasv1.MicroserviceBalancingRuleName))

			duplicate := valid.DeepCopy()
			duplicate.Spec.Rules = append(duplicate.Spec.Rules, dbaasv1.MicroserviceBalancingRuleItem{
				Type:          "postgresql",
				Label:         "zone=slow",
				Microservices: []string{"billing"},
			})
			reason = reconciler.validateMicroserviceRule(duplicate)
			Expect(reason).To(ContainSubstring("duplicate microservice"))
		})

		It("validates namespace rule list uniqueness locally", func() {
			reconciler := &BalancingRuleReconciler{}
			valid := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
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
			// The singleton name is enforced by the CRD, so the conflicting rule
			// must live in another namespace — which is also the real-world case:
			// aggregator rule names are global, one singleton per namespace.
			ns := &corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: conflictNS}}
			if err := k8sClient.Create(ctx, ns); err != nil {
				Expect(apierrors.IsAlreadyExists(err)).To(BeTrue(), "unexpected error creating %s: %v", conflictNS, err)
			}

			existing := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: conflictNS},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
						{Name: "orders-postgres-primary", Type: "postgresql", PhysicalDatabaseID: "postgresql-orders", Order: 10},
					},
				},
			}
			Expect(k8sClient.Create(ctx, existing)).To(Succeed())
			reconciler := &BalancingRuleReconciler{Client: k8sClient}

			duplicateName := &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
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

		It("rejects a non-singleton name at admission for all three kinds", func() {
			err := k8sClient.Create(ctx, &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: "wrong-name", Namespace: businessNS},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "postgresql", Label: "zone=fast", Microservices: []string{"billing"}},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring(dbaasv1.MicroserviceBalancingRuleName))

			err = k8sClient.Create(ctx, &dbaasv1.NamespaceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: "wrong-name", Namespace: businessNS},
				Spec: dbaasv1.NamespaceBalancingRuleSpec{
					Rules: []dbaasv1.NamespaceBalancingRuleItem{
						{Name: "pg-primary", Type: "postgresql", PhysicalDatabaseID: "postgresql-a", Order: 0},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring(dbaasv1.NamespaceBalancingRuleName))

			err = k8sClient.Create(ctx, &dbaasv1.PermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: "wrong-name", Namespace: operatorNS},
				Spec: dbaasv1.PermanentBalancingRuleSpec{
					Rules: []dbaasv1.PermanentBalancingRuleItem{
						{DbType: "postgresql", PhysicalDatabaseID: "postgresql-a", Namespaces: []string{"payments"}},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring(dbaasv1.PermanentBalancingRuleName))
		})

		It("rejects a whitespace-only value that MinLength alone would accept", func() {
			err := k8sClient.Create(ctx, &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: " ", Label: "zone=fast", Microservices: []string{"billing"}},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("spec.rules[0].type"))

			err = k8sClient.Create(ctx, &dbaasv1.MicroserviceBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.MicroserviceBalancingRuleName, Namespace: businessNS},
				Spec: dbaasv1.MicroserviceBalancingRuleSpec{
					Rules: []dbaasv1.MicroserviceBalancingRuleItem{
						{Type: "postgresql", Label: "zone=fast", Microservices: []string{" "}},
					},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("spec.rules[0].microservices[0]"))
		})

		It("accepts a valid permanent singleton and rejects operator namespace/duplicate target violations", func() {
			reconciler := &BalancingRuleReconciler{MyNamespace: operatorNS}
			valid := &dbaasv1.PermanentBalancingRule{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.PermanentBalancingRuleName, Namespace: operatorNS},
				Spec: dbaasv1.PermanentBalancingRuleSpec{
					Rules: []dbaasv1.PermanentBalancingRuleItem{
						{DBType: "postgresql", PhysicalDatabaseID: "postgresql-a", Namespaces: []string{"payments", "orders"}},
						{DBType: "mongodb", PhysicalDatabaseID: "mongodb-a", Namespaces: []string{"notifications"}},
					},
				},
			}

			reason := reconciler.validatePermanentRule(valid)
			Expect(reason).To(BeEmpty())

			foreignNamespace := valid.DeepCopy()
			foreignNamespace.Namespace = nsPayments
			reason = reconciler.validatePermanentRule(foreignNamespace)
			Expect(reason).To(ContainSubstring("operator namespace"))

			duplicateTarget := valid.DeepCopy()
			duplicateTarget.Spec.Rules = append(duplicateTarget.Spec.Rules, dbaasv1.PermanentBalancingRuleItem{
				DBType:             "postgresql",
				PhysicalDatabaseID: "postgresql-b",
				Namespaces:         []string{nsPayments},
			})
			reason = reconciler.validatePermanentRule(duplicateTarget)
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
