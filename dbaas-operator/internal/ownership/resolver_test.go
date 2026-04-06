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

package ownership_test

import (
	"context"
	"testing"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

func TestOwnership(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "Ownership Suite")
}

func newScheme() *runtime.Scheme {
	s := runtime.NewScheme()
	_ = dbaasv1.AddToScheme(s)
	return s
}

func makeBinding(namespace, location string) *dbaasv1.OperatorBinding {
	return &dbaasv1.OperatorBinding{
		ObjectMeta: metav1.ObjectMeta{
			Name:      dbaasv1.OperatorBindingName,
			Namespace: namespace,
		},
		Spec: dbaasv1.OperatorBindingSpec{Location: location},
	}
}

var _ = Describe("OwnershipResolver", func() {
	const myNS = "my-operator-ns"
	const ns1 = "ns1"
	const ns2 = "ns2"
	const otherNS = "other-operator-ns"

	ctx := context.Background()

	Describe("GetState / SetOwner / Forget", func() {
		It("returns Unknown for an unseen namespace", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			Expect(r.GetState(ns1)).To(Equal(ownership.Unknown))
		})

		It("returns Mine when location matches myNamespace", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			r.SetOwner(ns1, myNS)
			Expect(r.GetState(ns1)).To(Equal(ownership.Mine))
		})

		It("returns Foreign when location differs from myNamespace", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			r.SetOwner(ns1, otherNS)
			Expect(r.GetState(ns1)).To(Equal(ownership.Foreign))
		})

		It("Forget removes the cached entry", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			r.SetOwner(ns1, myNS)
			r.Forget(ns1)
			Expect(r.GetState(ns1)).To(Equal(ownership.Unknown))
		})

		It("Forget on an unknown namespace is a no-op", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			Expect(func() { r.Forget("never-seen") }).NotTo(Panic())
		})
	})

	Describe("IsMyNamespace — fast path (cached)", func() {
		It("returns true for a Mine entry", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			r.SetOwner(ns1, myNS)
			mine, err := r.IsMyNamespace(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(mine).To(BeTrue())
		})

		It("returns false for a Foreign entry", func() {
			r := ownership.NewOwnershipResolver(myNS, fake.NewClientBuilder().Build())
			r.SetOwner(ns1, otherNS)
			mine, err := r.IsMyNamespace(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(mine).To(BeFalse())
		})
	})

	Describe("IsMyNamespace — slow path (API lookup)", func() {
		It("returns true and populates cache when binding matches", func() {
			cl := fake.NewClientBuilder().
				WithScheme(newScheme()).
				WithObjects(makeBinding(ns1, myNS)).
				Build()
			r := ownership.NewOwnershipResolver(myNS, cl)

			mine, err := r.IsMyNamespace(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(mine).To(BeTrue())
			// Cache should now be populated.
			Expect(r.GetState(ns1)).To(Equal(ownership.Mine))
		})

		It("returns false and does not cache when no binding exists", func() {
			cl := fake.NewClientBuilder().WithScheme(newScheme()).Build()
			r := ownership.NewOwnershipResolver(myNS, cl)

			mine, err := r.IsMyNamespace(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(mine).To(BeFalse())
			// Not cached — a binding may appear later.
			Expect(r.GetState(ns1)).To(Equal(ownership.Unknown))
		})

		It("returns false when binding belongs to a different operator", func() {
			cl := fake.NewClientBuilder().
				WithScheme(newScheme()).
				WithObjects(makeBinding(ns1, otherNS)).
				Build()
			r := ownership.NewOwnershipResolver(myNS, cl)

			mine, err := r.IsMyNamespace(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(mine).To(BeFalse())
			Expect(r.GetState(ns1)).To(Equal(ownership.Foreign))
		})
	})

	Describe("WarmupOwnershipCache", func() {
		It("populates cache for all existing bindings", func() {
			cl := fake.NewClientBuilder().
				WithScheme(newScheme()).
				WithObjects(
					makeBinding(ns1, myNS),
					makeBinding(ns2, otherNS),
				).
				Build()
			r := ownership.NewOwnershipResolver(myNS, cl)

			Expect(r.WarmupOwnershipCache(ctx)).To(Succeed())
			Expect(r.GetState(ns1)).To(Equal(ownership.Mine))
			Expect(r.GetState(ns2)).To(Equal(ownership.Foreign))
		})

		It("leaves cache empty when no bindings exist", func() {
			cl := fake.NewClientBuilder().WithScheme(newScheme()).Build()
			r := ownership.NewOwnershipResolver(myNS, cl)

			Expect(r.WarmupOwnershipCache(ctx)).To(Succeed())
			Expect(r.GetState(ns1)).To(Equal(ownership.Unknown))
		})
	})

	Describe("OwnershipState.String", func() {
		It("returns readable names", func() {
			Expect(ownership.Mine.String()).To(Equal("Mine"))
			Expect(ownership.Foreign.String()).To(Equal("Foreign"))
			Expect(ownership.Unknown.String()).To(Equal("Unknown"))
		})
	})

	Describe("KindChecker", func() {
		It("returns false when namespace is empty", func() {
			cl := fake.NewClientBuilder().WithScheme(newScheme()).Build()
			checker := ownership.NewKindChecker(
				cl,
				func() *dbaasv1.OperatorBindingList { return &dbaasv1.OperatorBindingList{} },
				func(l *dbaasv1.OperatorBindingList) int { return len(l.Items) },
			)
			blocking, err := checker.HasBlockingResources(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(blocking).To(BeFalse())
		})

		It("returns true when objects exist in the namespace", func() {
			cl := fake.NewClientBuilder().
				WithScheme(newScheme()).
				WithObjects(makeBinding(ns1, myNS)).
				Build()
			checker := ownership.NewKindChecker(
				cl,
				func() *dbaasv1.OperatorBindingList { return &dbaasv1.OperatorBindingList{} },
				func(l *dbaasv1.OperatorBindingList) int { return len(l.Items) },
			)
			blocking, err := checker.HasBlockingResources(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(blocking).To(BeTrue())
		})
	})

	Describe("CompositeChecker", func() {
		makeChecker := func(result bool) ownership.BlockingResourceChecker {
			return &fixedChecker{result: result}
		}

		It("returns false when all checkers return false", func() {
			composite := ownership.NewCompositeChecker(makeChecker(false), makeChecker(false))
			blocking, err := composite.HasBlockingResources(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(blocking).To(BeFalse())
		})

		It("returns true as soon as any checker returns true (short-circuit)", func() {
			composite := ownership.NewCompositeChecker(makeChecker(false), makeChecker(true))
			blocking, err := composite.HasBlockingResources(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(blocking).To(BeTrue())
		})

		It("supports Add after construction", func() {
			composite := ownership.NewCompositeChecker()
			composite.Add(makeChecker(true))
			blocking, err := composite.HasBlockingResources(ctx, ns1)
			Expect(err).NotTo(HaveOccurred())
			Expect(blocking).To(BeTrue())
		})
	})
})

// fixedChecker is a BlockingResourceChecker that always returns the same result.
type fixedChecker struct {
	result bool
}

func (f *fixedChecker) HasBlockingResources(_ context.Context, _ string) (bool, error) {
	return f.result, nil
}

// Ensure fixedChecker implements the interface.
var _ ownership.BlockingResourceChecker = &fixedChecker{}

// Ensure fake client satisfies client.Client (compile-time check).
var _ client.Client = fake.NewClientBuilder().Build()
