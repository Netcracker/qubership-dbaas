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
	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

// PermanentBalancingRule's operatorNamespace markers are covered in
// balancingrule_validation_test.go. These specs guard the identical Required +
// MinLength=1 + Pattern (non-whitespace) + immutability markers on the other six
// managed kinds, so a dropped marker or a mis-generated CRD on any of them is caught
// by admission instead of shipping with a green suite.
var _ = Describe("Operator assignment admission", func() {
	assignmentTestNamespace := "default"

	newManagedCR := func(kind, name string, spec map[string]any) *unstructured.Unstructured {
		return &unstructured.Unstructured{
			Object: map[string]any{
				"apiVersion": "dbaas.netcracker.com/v1",
				"kind":       kind,
				"metadata": map[string]any{
					"name":      name,
					"namespace": assignmentTestNamespace,
				},
				"spec": spec,
			},
		}
	}

	DescribeTable("rejects a managed CR that omits spec.operatorNamespace",
		func(kind, name string) {
			err := k8sClient.Create(ctx, newManagedCR(kind, name, map[string]any{}))

			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("operatorNamespace"))
		},
		Entry("ExternalDatabase", "ExternalDatabase", "edb-missing-op-ns"),
		Entry("InternalDatabase", "InternalDatabase", "idb-missing-op-ns"),
		Entry("DatabaseAccessPolicy", "DatabaseAccessPolicy", "dap-missing-op-ns"),
		Entry("DatabaseSecretClaim", "DatabaseSecretClaim", "dsc-missing-op-ns"),
		Entry("MicroserviceBalancingRule", "MicroserviceBalancingRule", dbaasv1.MicroserviceBalancingRuleName),
		Entry("NamespaceBalancingRule", "NamespaceBalancingRule", dbaasv1.NamespaceBalancingRuleName),
	)

	DescribeTable("rejects an empty or whitespace-only spec.operatorNamespace",
		func(kind, name, value string) {
			err := k8sClient.Create(ctx, newManagedCR(kind, name, map[string]any{"operatorNamespace": value}))

			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("operatorNamespace"))
		},
		Entry("ExternalDatabase empty", "ExternalDatabase", "edb-blank-op-ns", ""),
		Entry("ExternalDatabase whitespace", "ExternalDatabase", "edb-space-op-ns", " "),
		Entry("InternalDatabase empty", "InternalDatabase", "idb-blank-op-ns", ""),
		Entry("InternalDatabase whitespace", "InternalDatabase", "idb-space-op-ns", " "),
		Entry("DatabaseAccessPolicy empty", "DatabaseAccessPolicy", "dap-blank-op-ns", ""),
		Entry("DatabaseAccessPolicy whitespace", "DatabaseAccessPolicy", "dap-space-op-ns", " "),
		Entry("DatabaseSecretClaim empty", "DatabaseSecretClaim", "dsc-blank-op-ns", ""),
		Entry("DatabaseSecretClaim whitespace", "DatabaseSecretClaim", "dsc-space-op-ns", " "),
		Entry("MicroserviceBalancingRule empty", "MicroserviceBalancingRule", dbaasv1.MicroserviceBalancingRuleName, ""),
		Entry("MicroserviceBalancingRule whitespace", "MicroserviceBalancingRule", dbaasv1.MicroserviceBalancingRuleName, " "),
		Entry("NamespaceBalancingRule empty", "NamespaceBalancingRule", dbaasv1.NamespaceBalancingRuleName, ""),
		Entry("NamespaceBalancingRule whitespace", "NamespaceBalancingRule", dbaasv1.NamespaceBalancingRuleName, " "),
	)

	DescribeTable("rejects a change to spec.operatorNamespace after creation",
		func(kind, name string, validSpec map[string]any) {
			obj := newManagedCR(kind, name, validSpec)
			Expect(k8sClient.Create(ctx, obj)).To(Succeed())
			DeferCleanup(func() { Expect(k8sClient.Delete(ctx, obj)).To(Succeed()) })

			Expect(unstructured.SetNestedField(obj.Object, "reassigned-operator", "spec", "operatorNamespace")).To(Succeed())
			err := k8sClient.Update(ctx, obj)

			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("immutable"))
		},
		Entry("ExternalDatabase", "ExternalDatabase", "edb-immutable-op-ns", map[string]any{
			"operatorNamespace":    "assign-a",
			"classifier":           map[string]any{"microserviceName": "svc", "scope": "service"},
			"type":                 "postgresql",
			"dbName":               "immutable-edb",
			"connectionProperties": []any{map[string]any{"role": "admin"}},
		}),
		Entry("InternalDatabase", "InternalDatabase", "idb-immutable-op-ns", map[string]any{
			"operatorNamespace": "assign-a",
			"classifier":        map[string]any{"microserviceName": "svc", "scope": "service"},
			"type":              "postgresql",
		}),
		Entry("DatabaseAccessPolicy", "DatabaseAccessPolicy", "dap-immutable-op-ns", map[string]any{
			"operatorNamespace": "assign-a",
			"microserviceName":  "svc",
			"services":          []any{map[string]any{"name": "other-svc", "roles": []any{"admin"}}},
		}),
		Entry("DatabaseSecretClaim", "DatabaseSecretClaim", "dsc-immutable-op-ns", map[string]any{
			"operatorNamespace": "assign-a",
			"classifier":        map[string]any{"microserviceName": "svc", "scope": "service"},
			"type":              "postgresql",
			"userRole":          "admin",
			"secretName":        "immutable-dsc-secret",
		}),
		Entry("MicroserviceBalancingRule", "MicroserviceBalancingRule", dbaasv1.MicroserviceBalancingRuleName, map[string]any{
			"operatorNamespace": "assign-a",
			"rules": []any{map[string]any{
				"type": "mongodb", "label": "tier=gold", "microservices": []any{"billing"},
			}},
		}),
		Entry("NamespaceBalancingRule", "NamespaceBalancingRule", dbaasv1.NamespaceBalancingRuleName, map[string]any{
			"operatorNamespace": "assign-a",
			"rules": []any{map[string]any{
				"name": "payments-mongo", "type": "mongodb", "physicalDatabaseId": "mongodb-payments", "order": int64(10),
			}},
		}),
	)
})
