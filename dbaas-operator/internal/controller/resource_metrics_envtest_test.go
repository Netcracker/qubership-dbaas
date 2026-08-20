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

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

// These specs exercise the orphan scan (dbaas_resource_unassigned) against a real
// API server, because it relies on a server-side spec.operatorNamespace != selector
// that the fake client used by the unit tests does not honor.
var _ = Describe("Unassigned resource metrics", func() {
	const (
		collectorNamespace = "unassigned-collector-ns"
		crNamespace        = "default"
	)

	newInternalDatabase := func(name, operatorNamespace string) *dbaasv1.InternalDatabase {
		return &dbaasv1.InternalDatabase{
			ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: crNamespace},
			Spec: dbaasv1.InternalDatabaseSpec{
				OperatorNamespace: operatorNamespace,
				Classifier:        dbaasv1.Classifier{MicroserviceName: "unassigned-svc", Scope: "service"},
				Type:              "postgresql",
			},
		}
	}

	gather := func() []*dto.MetricFamily {
		collector := &resourceMetricsCollector{client: k8sClient, reader: k8sClient, operatorNamespace: collectorNamespace}
		registry := prometheus.NewRegistry()
		Expect(registry.Register(collector)).To(Succeed())
		families, err := registry.Gather()
		Expect(err).NotTo(HaveOccurred())
		return families
	}

	It("emits dbaas_resource_unassigned and scan-success for a CR owned by another operator", func() {
		orphan := newInternalDatabase("orphan-idb", "some-other-operator")
		Expect(k8sClient.Create(ctx, orphan)).To(Succeed())
		DeferCleanup(func() { Expect(k8sClient.Delete(ctx, orphan)).To(Succeed()) })

		Eventually(func(g Gomega) {
			families := gather()
			g.Expect(metricValue(families, "dbaas_resource_unassigned", map[string]string{
				"kind": resourceKindInternalDatabase, "resource_namespace": crNamespace, "name": "orphan-idb",
			})).To(Equal(float64(1)))
			g.Expect(metricValue(families, "dbaas_resource_unassigned_scan_success", map[string]string{
				"kind": resourceKindInternalDatabase,
			})).To(Equal(float64(1)))
		}).Should(Succeed())
	})

	It("does not emit dbaas_resource_unassigned for a CR owned by this operator", func() {
		owned := newInternalDatabase("owned-idb", collectorNamespace)
		Expect(k8sClient.Create(ctx, owned)).To(Succeed())
		DeferCleanup(func() { Expect(k8sClient.Delete(ctx, owned)).To(Succeed()) })

		Eventually(func(g Gomega) {
			families := gather()
			g.Expect(metricValue(families, "dbaas_resource_unassigned_scan_success", map[string]string{
				"kind": resourceKindInternalDatabase,
			})).To(Equal(float64(1)))
			g.Expect(metricValue(families, "dbaas_resource_unassigned", map[string]string{
				"name": "owned-idb",
			})).To(Equal(float64(-1)))
		}).Should(Succeed())
	})
})
