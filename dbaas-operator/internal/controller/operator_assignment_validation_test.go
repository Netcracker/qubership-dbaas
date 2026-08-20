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

// PermanentBalancingRule's required + immutable operatorNamespace marker is covered
// in balancingrule_validation_test.go. These specs guard the identical marker on the
// other six managed kinds, so a dropped Required/MinLength marker on any of them (or a
// mis-generated CRD) is caught by admission instead of shipping with a green suite.
var _ = Describe("Operator assignment admission", func() {
	DescribeTable("rejects a managed CR that omits spec.operatorNamespace",
		func(kind, name string) {
			obj := &unstructured.Unstructured{
				Object: map[string]any{
					"apiVersion": "dbaas.netcracker.com/v1",
					"kind":       kind,
					"metadata": map[string]any{
						"name":      name,
						"namespace": "default",
					},
					"spec": map[string]any{},
				},
			}

			err := k8sClient.Create(ctx, obj)

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
})
