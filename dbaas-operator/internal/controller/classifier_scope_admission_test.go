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

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

// The CRD schema pins spec.classifier.scope to an enum, so the API server rejects
// an unknown value at admission; the controllers never see it. These specs are
// otherwise valid, and only the scope varies.
var _ = Describe("Classifier scope CRD admission", func() {
	const admissionNS = "default"

	newExternalDatabase := func(scope string) *dbaasv1.ExternalDatabase {
		return &dbaasv1.ExternalDatabase{
			ObjectMeta: metav1.ObjectMeta{Name: "scope-admission-edb", Namespace: admissionNS},
			Spec: dbaasv1.ExternalDatabaseSpec{
				OperatorNamespace: testOperatorNamespace,
				Classifier:        dbaasv1.Classifier{MicroserviceName: "svc", Scope: scope},
				Type:              "postgresql",
				DBName:            "testdb",
				ConnectionProperties: []dbaasv1.ConnectionProperty{
					{Role: "admin"},
				},
			},
		}
	}

	newInternalDatabase := func(scope string) *dbaasv1.InternalDatabase {
		return &dbaasv1.InternalDatabase{
			ObjectMeta: metav1.ObjectMeta{Name: "scope-admission-idb", Namespace: admissionNS},
			Spec: dbaasv1.InternalDatabaseSpec{
				OperatorNamespace: testOperatorNamespace,
				Classifier:        dbaasv1.Classifier{MicroserviceName: "svc", Scope: scope},
				Type:              "postgresql",
			},
		}
	}

	newDatabaseSecretClaim := func(scope string) *dbaasv1.DatabaseSecretClaim {
		return &dbaasv1.DatabaseSecretClaim{
			ObjectMeta: metav1.ObjectMeta{Name: "scope-admission-dsc", Namespace: admissionNS},
			Spec: dbaasv1.DatabaseSecretClaimSpec{
				OperatorNamespace: testOperatorNamespace,
				Classifier:        dbaasv1.Classifier{MicroserviceName: "svc", Scope: scope},
				Type:              "postgresql",
				SecretName:        "scope-admission-secret",
			},
		}
	}

	DescribeTable("rejects an unknown scope at admission",
		func(makeObj func(string) client.Object) {
			err := k8sClient.Create(ctx, makeObj("Service"))
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("scope"))
		},
		Entry("ExternalDatabase", func(s string) client.Object { return newExternalDatabase(s) }),
		Entry("InternalDatabase", func(s string) client.Object { return newInternalDatabase(s) }),
		Entry("DatabaseSecretClaim", func(s string) client.Object { return newDatabaseSecretClaim(s) }),
	)

	DescribeTable("admits service and tenant",
		func(makeObj func(string) client.Object) {
			for _, scope := range []string{dbaasv1.ScopeService, dbaasv1.ScopeTenant} {
				obj := makeObj(scope)
				Expect(k8sClient.Create(ctx, obj)).To(Succeed())
				deleteIfExists(obj)
			}
		},
		Entry("ExternalDatabase", func(s string) client.Object { return newExternalDatabase(s) }),
		Entry("InternalDatabase", func(s string) client.Object { return newInternalDatabase(s) }),
		Entry("DatabaseSecretClaim", func(s string) client.Object { return newDatabaseSecretClaim(s) }),
	)
})
