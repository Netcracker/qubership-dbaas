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
	"context"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
)

var _ = Describe("OperatorBinding Controller", func() {
	const (
		ns          = "default"
		myLocation  = "my-operator-ns"
		otherLoc    = "other-operator-ns"
		bindingName = dbaasv1.OperatorBindingName
	)

	var (
		resolver       *ownership.OwnershipResolver
		fakeRecorder   *record.FakeRecorder
		reconciler     *OperatorBindingReconciler
		namespacedName types.NamespacedName
	)

	newBinding := func(location string) *dbaasv1.OperatorBinding {
		return &dbaasv1.OperatorBinding{
			ObjectMeta: metav1.ObjectMeta{
				Name:      bindingName,
				Namespace: ns,
			},
			Spec: dbaasv1.OperatorBindingSpec{Location: location},
		}
	}

	reconcileBinding := func() (*dbaasv1.OperatorBinding, reconcile.Result, error) {
		return reconcileAndFetchObject(reconciler, namespacedName, func() *dbaasv1.OperatorBinding {
			return &dbaasv1.OperatorBinding{}
		})
	}

	BeforeEach(func() {
		namespacedName = types.NamespacedName{Name: bindingName, Namespace: ns}
		fakeRecorder = record.NewFakeRecorder(16)
		resolver = ownership.NewOwnershipResolver(myLocation, k8sClient)
		reconciler = &OperatorBindingReconciler{
			Client:      k8sClient,
			Scheme:      k8sClient.Scheme(),
			Recorder:    fakeRecorder,
			MyNamespace: myLocation,
			Ownership:   resolver,
			Checker:     ownership.NewCompositeChecker(),
		}
	})

	AfterEach(func() {
		// Delete the binding if it still exists (force removal of finalizer first).
		ob := &dbaasv1.OperatorBinding{}
		if err := k8sClient.Get(ctx, namespacedName, ob); err == nil {
			if controllerutil.ContainsFinalizer(ob, dbaasv1.OperatorBindingProtectionFinalizer) {
				patch := client.MergeFrom(ob.DeepCopy())
				controllerutil.RemoveFinalizer(ob, dbaasv1.OperatorBindingProtectionFinalizer)
				_ = k8sClient.Patch(ctx, ob, patch)
			}
			_ = k8sClient.Delete(ctx, ob)
		}
		drainRecordedEvents(fakeRecorder.Events)
	})

	// ── Happy path: create ───────────────────────────────────────────────────

	Context("when an OperatorBinding is created", func() {
		It("adds the protection finalizer and updates the ownership cache", func() {
			ob := newBinding(myLocation)
			Expect(k8sClient.Create(ctx, ob)).To(Succeed())

			fetched, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			Expect(controllerutil.ContainsFinalizer(fetched, dbaasv1.OperatorBindingProtectionFinalizer)).To(BeTrue())

			// Ownership cache must reflect Mine.
			Expect(resolver.GetState(ns)).To(Equal(ownership.Mine))
		})

		It("emits a BindingRegistered event", func() {
			Expect(k8sClient.Create(ctx, newBinding(myLocation))).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeNormal, EventReasonBindingRegistered)
		})

		It("marks the namespace as Foreign for a binding owned by a different operator", func() {
			Expect(k8sClient.Create(ctx, newBinding(otherLoc))).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			Expect(resolver.GetState(ns)).To(Equal(ownership.Foreign))
		})
	})

	// ── Second reconcile: finalizer already present ──────────────────────────

	Context("when the finalizer is already present", func() {
		It("does not re-add it or emit another event", func() {
			ob := newBinding(myLocation)
			Expect(k8sClient.Create(ctx, ob)).To(Succeed())

			// First reconcile — adds finalizer + emits event.
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			// Second reconcile — no-op.
			_, _, err = reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── Deletion path: no blocking resources ─────────────────────────────────

	Context("when the OperatorBinding is deleted and no blocking resources exist", func() {
		It("removes the finalizer and forgets the namespace", func() {
			ob := newBinding(myLocation)
			Expect(k8sClient.Create(ctx, ob)).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			// Trigger deletion.
			Expect(k8sClient.Delete(ctx, &dbaasv1.OperatorBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			// Reconcile the deletion.
			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))

			// Binding should now be gone (finalizer removed → GC collected it).
			err = k8sClient.Get(ctx, namespacedName, &dbaasv1.OperatorBinding{})
			Expect(client.IgnoreNotFound(err)).To(BeNil())

			// Cache must be cleared.
			Expect(resolver.GetState(ns)).To(Equal(ownership.Unknown))
		})
	})

	// ── Deletion path: blocking resources present ────────────────────────────

	Context("when the OperatorBinding is deleted but blocking resources exist", func() {
		It("keeps the finalizer and emits a BindingBlocked warning", func() {
			// Use a checker that always reports blocking.
			reconciler.Checker = &alwaysBlockingChecker{}

			ob := newBinding(myLocation)
			Expect(k8sClient.Create(ctx, ob)).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			// Trigger deletion.
			Expect(k8sClient.Delete(ctx, &dbaasv1.OperatorBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			// Reconcile — should keep the finalizer.
			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))

			// Binding must still exist (finalizer not removed).
			fetched := &dbaasv1.OperatorBinding{}
			Expect(k8sClient.Get(ctx, namespacedName, fetched)).To(Succeed())
			Expect(controllerutil.ContainsFinalizer(fetched, dbaasv1.OperatorBindingProtectionFinalizer)).To(BeTrue())

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonBindingBlocked)
		})
	})

	// ── Ownership cache on Not-Found ─────────────────────────────────────────

	Context("when the OperatorBinding does not exist", func() {
		It("forgets the namespace and returns no error", func() {
			// Pre-seed cache so Forget has something to clear.
			resolver.SetOwner(ns, myLocation)
			Expect(resolver.GetState(ns)).To(Equal(ownership.Mine))

			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))
			Expect(resolver.GetState(ns)).To(Equal(ownership.Unknown))
		})
	})
})

// alwaysBlockingChecker is a BlockingResourceChecker that always returns true.
type alwaysBlockingChecker struct{}

func (a *alwaysBlockingChecker) HasBlockingResources(_ context.Context, _ string) (bool, error) {
	return true, nil
}
