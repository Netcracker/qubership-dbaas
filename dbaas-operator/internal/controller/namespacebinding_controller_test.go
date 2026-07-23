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
	"errors"

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

var _ = Describe("NamespaceBinding Controller", func() {
	const (
		ns              = "default"
		myOperatorNS    = "my-operator-ns"
		otherOperatorNS = "other-operator-ns"
		bindingName     = dbaasv1.NamespaceBindingName
	)

	var (
		resolver       *ownership.OwnershipResolver
		fakeRecorder   *record.FakeRecorder
		reconciler     *NamespaceBindingReconciler
		namespacedName types.NamespacedName
	)

	newBinding := func(operatorNamespace string) *dbaasv1.NamespaceBinding {
		return &dbaasv1.NamespaceBinding{
			ObjectMeta: metav1.ObjectMeta{
				Name:      bindingName,
				Namespace: ns,
			},
			Spec: dbaasv1.NamespaceBindingSpec{OperatorNamespace: operatorNamespace},
		}
	}

	reconcileBinding := func() (*dbaasv1.NamespaceBinding, reconcile.Result, error) {
		return reconcileAndFetchObject(reconciler, namespacedName, func() *dbaasv1.NamespaceBinding {
			return &dbaasv1.NamespaceBinding{}
		})
	}

	BeforeEach(func() {
		namespacedName = types.NamespacedName{Name: bindingName, Namespace: ns}
		fakeRecorder = record.NewFakeRecorder(16)
		resolver = ownership.NewOwnershipResolver(myOperatorNS, k8sClient)
		reconciler = &NamespaceBindingReconciler{
			Client:      k8sClient,
			Scheme:      k8sClient.Scheme(),
			Recorder:    fakeRecorder,
			MyNamespace: myOperatorNS,
			Ownership:   resolver,
			Checker:     ownership.NewCompositeChecker(),
		}
	})

	AfterEach(func() {
		// Delete the binding if it still exists (force removal of finalizer first).
		nb := &dbaasv1.NamespaceBinding{}
		if err := k8sClient.Get(ctx, namespacedName, nb); err == nil {
			if controllerutil.ContainsFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer) {
				patch := client.MergeFrom(nb.DeepCopy())
				controllerutil.RemoveFinalizer(nb, dbaasv1.NamespaceBindingProtectionFinalizer)
				_ = k8sClient.Patch(ctx, nb, patch)
			}
			_ = k8sClient.Delete(ctx, nb)
		}
		drainRecordedEvents(fakeRecorder.Events)
	})

	// ── Happy path: create ───────────────────────────────────────────────────

	Context("when a NamespaceBinding is created", func() {
		It("adds the protection finalizer and updates the ownership cache", func() {
			nb := newBinding(myOperatorNS)
			Expect(k8sClient.Create(ctx, nb)).To(Succeed())

			fetched, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			Expect(controllerutil.ContainsFinalizer(fetched, dbaasv1.NamespaceBindingProtectionFinalizer)).To(BeTrue())

			// Ownership cache must reflect Mine.
			Expect(resolver.GetState(ns)).To(Equal(ownership.Mine))
		})

		It("marks the binding Ready and stamps observedGeneration", func() {
			Expect(k8sClient.Create(ctx, newBinding(myOperatorNS))).To(Succeed())

			fetched, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())

			Expect(fetched.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			ready := findCondition(fetched.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonBindingRegistered))
			stalled := findCondition(fetched.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(fetched.Status.ObservedGeneration).To(Equal(fetched.Generation))
			Expect(fetched.Status.LastRequestID).NotTo(BeEmpty())
		})

		It("emits a BindingRegistered event", func() {
			Expect(k8sClient.Create(ctx, newBinding(myOperatorNS))).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeNormal, EventReasonBindingRegistered)
		})

		It("marks the namespace as Foreign for a binding owned by a different operator", func() {
			Expect(k8sClient.Create(ctx, newBinding(otherOperatorNS))).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			Expect(resolver.GetState(ns)).To(Equal(ownership.Foreign))
		})
	})

	// ── Second reconcile: finalizer already present ──────────────────────────

	Context("when the finalizer is already present", func() {
		It("does not re-add it or emit another event", func() {
			nb := newBinding(myOperatorNS)
			Expect(k8sClient.Create(ctx, nb)).To(Succeed())

			// First reconcile — adds finalizer + emits event.
			first, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			// Second reconcile — no-op: no event and, because nothing in the
			// status changed, no status write either (workload churn re-enqueues
			// the binding constantly; a per-reconcile write would amplify it).
			second, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			expectNoRecordedEvent(fakeRecorder.Events)
			Expect(second.ResourceVersion).To(Equal(first.ResourceVersion))
			Expect(second.Status.LastRequestID).To(Equal(first.Status.LastRequestID))
		})
	})

	// ── Deletion path: no blocking resources ─────────────────────────────────

	Context("when the NamespaceBinding is deleted and no blocking resources exist", func() {
		It("removes the finalizer and forgets the namespace", func() {
			nb := newBinding(myOperatorNS)
			Expect(k8sClient.Create(ctx, nb)).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			// Trigger deletion.
			Expect(k8sClient.Delete(ctx, &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			// Reconcile the deletion.
			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))

			// Binding should now be gone (finalizer removed → GC collected it).
			err = k8sClient.Get(ctx, namespacedName, &dbaasv1.NamespaceBinding{})
			Expect(client.IgnoreNotFound(err)).To(Succeed())

			// Cache must be cleared.
			Expect(resolver.GetState(ns)).To(Equal(ownership.Unknown))
		})
	})

	// ── Deletion path: blocking resources present ────────────────────────────

	Context("when the NamespaceBinding is deleted but blocking resources exist", func() {
		It("keeps the finalizer and emits a BindingBlocked warning", func() {
			// Use a checker that always reports blocking.
			reconciler.Checker = &alwaysBlockingChecker{}

			nb := newBinding(myOperatorNS)
			Expect(k8sClient.Create(ctx, nb)).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			// Trigger deletion.
			Expect(k8sClient.Delete(ctx, &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			// Reconcile — should keep the finalizer.
			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))

			// Binding must still exist (finalizer not removed).
			fetched := &dbaasv1.NamespaceBinding{}
			Expect(k8sClient.Get(ctx, namespacedName, fetched)).To(Succeed())
			Expect(controllerutil.ContainsFinalizer(fetched, dbaasv1.NamespaceBindingProtectionFinalizer)).To(BeTrue())

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonBindingBlocked)
		})

		It("reports the blocking kinds in the Ready condition and does not stamp observedGeneration", func() {
			reconciler.Checker = &alwaysBlockingChecker{}

			Expect(k8sClient.Create(ctx, newBinding(myOperatorNS))).To(Succeed())
			fetched, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			generationBeforeDelete := fetched.Generation
			Expect(fetched.Status.ObservedGeneration).To(Equal(generationBeforeDelete))

			Expect(k8sClient.Delete(ctx, &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			fetched, _, err = reconcileBinding()
			Expect(err).NotTo(HaveOccurred())

			Expect(fetched.Status.Phase).To(Equal(dbaasv1.PhaseProcessing))
			ready := findCondition(fetched.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonBindingBlocked))
			Expect(ready.Message).To(ContainSubstring("InternalDatabase, DatabaseSecretClaim"))
			stalled := findCondition(fetched.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			// Deletion bumped the generation; a blocked deletion is not terminal,
			// so observedGeneration must stay at the pre-deletion value.
			Expect(fetched.Generation).To(BeNumerically(">", generationBeforeDelete))
			Expect(fetched.Status.ObservedGeneration).To(Equal(generationBeforeDelete))
		})
	})

	// ── Deletion path: blocking-resource check fails ─────────────────────────

	Context("when the blocking-resource check fails during deletion", func() {
		It("marks a transient failure and returns the error", func() {
			reconciler.Checker = &failingChecker{}

			Expect(k8sClient.Create(ctx, newBinding(myOperatorNS))).To(Succeed())
			_, _, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fakeRecorder.Events)

			Expect(k8sClient.Delete(ctx, &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			fetched, _, err := reconcileBinding()
			Expect(err).To(HaveOccurred())

			Expect(fetched.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			ready := findCondition(fetched.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(ReasonOwnershipCheckError))
			Expect(ready.Message).To(ContainSubstring("connection refused"))
		})
	})

	// ── Foreign binding — no mutations ───────────────────────────────────────

	Context("when the NamespaceBinding belongs to a different operator instance", func() {
		It("updates the cache to Foreign but does not add the finalizer or emit events", func() {
			nb := &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
				Spec:       dbaasv1.NamespaceBindingSpec{OperatorNamespace: otherOperatorNS},
			}
			Expect(k8sClient.Create(ctx, nb)).To(Succeed())

			_, result, err := reconcileBinding()
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))

			// Cache must reflect Foreign, not Mine.
			Expect(resolver.GetState(ns)).To(Equal(ownership.Foreign))

			// The owning operator must NOT have added the finalizer.
			fetched := &dbaasv1.NamespaceBinding{}
			Expect(k8sClient.Get(ctx, namespacedName, fetched)).To(Succeed())
			Expect(controllerutil.ContainsFinalizer(fetched, dbaasv1.NamespaceBindingProtectionFinalizer)).To(BeFalse())

			// Status belongs to the owning instance — a foreign instance must
			// leave it completely empty.
			Expect(fetched.Status.Phase).To(BeEmpty())
			Expect(fetched.Status.Conditions).To(BeEmpty())
			Expect(fetched.Status.ObservedGeneration).To(BeZero())

			// No events must be emitted.
			expectNoRecordedEvent(fakeRecorder.Events)
		})

		It("does not remove the finalizer during deletion even if no blocking resources exist", func() {
			// Simulate a foreign binding that somehow already has the finalizer
			// (placed by the owning operator). This instance must not remove it.
			nb := &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{
					Name:       bindingName,
					Namespace:  ns,
					Finalizers: []string{dbaasv1.NamespaceBindingProtectionFinalizer},
				},
				Spec: dbaasv1.NamespaceBindingSpec{OperatorNamespace: otherOperatorNS},
			}
			Expect(k8sClient.Create(ctx, nb)).To(Succeed())

			// Trigger deletion.
			Expect(k8sClient.Delete(ctx, &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: bindingName, Namespace: ns},
			})).To(Succeed())

			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))

			// Finalizer must still be present — this instance must not touch it.
			fetched := &dbaasv1.NamespaceBinding{}
			Expect(k8sClient.Get(ctx, namespacedName, fetched)).To(Succeed())
			Expect(controllerutil.ContainsFinalizer(fetched, dbaasv1.NamespaceBindingProtectionFinalizer)).To(BeTrue())

			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── Ownership cache on Not-Found ─────────────────────────────────────────

	Context("when the NamespaceBinding does not exist", func() {
		It("forgets the namespace and returns no error", func() {
			// Pre-seed cache so Forget has something to clear.
			resolver.SetOwner(ns, myOperatorNS)
			Expect(resolver.GetState(ns)).To(Equal(ownership.Mine))

			result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(result).To(Equal(reconcile.Result{}))
			Expect(resolver.GetState(ns)).To(Equal(ownership.Unknown))
		})
	})
})

// alwaysBlockingChecker is a BlockingResourceChecker that always reports two
// blocking kinds.
type alwaysBlockingChecker struct{}

func (a *alwaysBlockingChecker) BlockingKinds(_ context.Context, _ string) ([]string, error) {
	return []string{"InternalDatabase", "DatabaseSecretClaim"}, nil
}

// failingChecker is a BlockingResourceChecker whose check always fails.
type failingChecker struct{}

func (f *failingChecker) BlockingKinds(_ context.Context, _ string) ([]string, error) {
	return nil, errors.New("list InternalDatabase: connection refused")
}
