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
	"net/http"
	"net/http/httptest"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

// findCondition returns the first condition with the given type, or nil.
func findCondition(conditions []metav1.Condition, condType string) *metav1.Condition {
	for i := range conditions {
		if conditions[i].Type == condType {
			return &conditions[i]
		}
	}
	return nil
}

var _ = Describe("ExternalDatabaseDeclaration Controller", func() {
	const (
		ns           = "default"
		resourceName = "test-edb"
		secretName   = "test-edb-secret"
	)

	var (
		mockServer     *httptest.Server
		mockStatusCode int
		reconciler     *ExternalDatabaseDeclarationReconciler
		fakeRecorder   *record.FakeRecorder
		namespacedName types.NamespacedName
	)

	// baseSpec builds a minimal valid spec without any credentialsSecretRef.
	// Suitable for tests that only care about the aggregator response.
	baseSpec := func() dbaasv1alpha1.ExternalDatabaseDeclarationSpec {
		return dbaasv1alpha1.ExternalDatabaseDeclarationSpec{
			Classifier: map[string]string{
				"microserviceName": "test-service",
				"namespace":        ns,
				"scope":            "service",
			},
			Type:   "postgresql",
			DbName: "testdb",
			ConnectionProperties: []dbaasv1alpha1.ConnectionProperty{
				{Role: "admin", URL: "jdbc:postgresql://pg:5432/testdb"},
			},
		}
	}

	BeforeEach(func() {
		mockStatusCode = http.StatusOK
		mockServer = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(mockStatusCode)
		}))

		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}

		// Buffered channel — large enough that a single reconcile never blocks.
		fakeRecorder = record.NewFakeRecorder(16)

		reconciler = &ExternalDatabaseDeclarationReconciler{
			Client:     k8sClient,
			Scheme:     k8sClient.Scheme(),
			Aggregator: aggregatorclient.NewAggregatorClient(mockServer.URL, "user", "pass"),
			Recorder:   fakeRecorder,
		}
	})

	AfterEach(func() {
		mockServer.Close()

		edb := &dbaasv1alpha1.ExternalDatabaseDeclaration{}
		if err := k8sClient.Get(ctx, namespacedName, edb); err == nil {
			Expect(k8sClient.Delete(ctx, edb)).To(Succeed())
		}

		secret := &corev1.Secret{}
		if err := k8sClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, secret); err == nil {
			Expect(k8sClient.Delete(ctx, secret)).To(Succeed())
		}

		// Drain any unconsumed events so a missed assertion in one test does
		// not leak into the next test's recorder channel.
		for {
			select {
			case <-fakeRecorder.Events:
			default:
				return
			}
		}
	})

	// reconcileAndFetch calls Reconcile and re-fetches the CR from the API server.
	// The CR must be created before calling this helper.
	reconcileAndFetch := func() (*dbaasv1alpha1.ExternalDatabaseDeclaration, reconcile.Result, error) {
		result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
		fetched := &dbaasv1alpha1.ExternalDatabaseDeclaration{}
		Expect(k8sClient.Get(ctx, namespacedName, fetched)).To(Succeed())
		return fetched, result, err
	}

	// expectEvent asserts that the next item in the recorder channel starts with
	// "<eventtype> <reason>" — it does not check the trailing message text so that
	// minor wording changes do not break the test.
	//
	// record.FakeRecorder writes events as: eventtype + " " + reason + " " + message
	// (see k8s.io/client-go/tools/record.FakeRecorder.Event).
	//
	// Events are sent synchronously inside r.Recorder.Event / r.Recorder.Eventf,
	// so the event is already in the channel when Reconcile returns — no Eventually
	// is needed.
	expectEvent := func(eventtype, reason string) {
		GinkgoHelper()
		Expect(fakeRecorder.Events).To(Receive(HavePrefix(eventtype + " " + reason)))
	}

	// expectNoEvent asserts that no event was emitted.
	expectNoEvent := func() {
		GinkgoHelper()
		Expect(fakeRecorder.Events).NotTo(Receive())
	}

	// ── Success cases ─────────────────────────────────────────────────────────

	Context("HTTP 200 — database already registered (no update)", func() {
		It("sets Phase=Updated and Registered=True, emits Normal/Registered event, does not requeue", func() {
			mockStatusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseUpdated))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionTrue))
			Expect(cond.Reason).To(Equal("Registered"))
			expectEvent(corev1.EventTypeNormal, EventReasonRegistered)
			expectNoEvent()
		})
	})

	Context("HTTP 201 — database successfully created or updated", func() {
		It("sets Phase=Updated and Registered=True, emits Normal/Registered event, does not requeue", func() {
			mockStatusCode = http.StatusCreated
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseUpdated))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionTrue))
			Expect(cond.Reason).To(Equal("Registered"))
			expectEvent(corev1.EventTypeNormal, EventReasonRegistered)
			expectNoEvent()
		})
	})

	// ── Secret resolution error ───────────────────────────────────────────────

	Context("Secret referenced in credentialsSecretRef does not exist", func() {
		It("sets Phase=BackingOff and SecretError condition, emits Warning/SecretError event, requeues", func() {
			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1alpha1.CredentialsSecretRef{
				Name: "non-existent-secret",
			}
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("SecretError"))
			expectEvent(corev1.EventTypeWarning, EventReasonSecretError)
			expectNoEvent()
		})
	})

	// ── Aggregator 4xx errors ─────────────────────────────────────────────────

	Context("HTTP 400 — invalid request (e.g. invalid classifier)", func() {
		It("sets Phase=InvalidConfiguration, emits Warning/AggregatorRejected event, does not requeue", func() {
			mockStatusCode = http.StatusBadRequest
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("AggregatorRejected"))
			expectEvent(corev1.EventTypeWarning, EventReasonAggregatorRejected)
			expectNoEvent()
		})
	})

	Context("HTTP 401 — operator credentials or role binding misconfigured", func() {
		It("sets Phase=BackingOff and Unauthorized condition, emits Warning/Unauthorized event, requeues", func() {
			mockStatusCode = http.StatusUnauthorized
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("Unauthorized"))
			expectEvent(corev1.EventTypeWarning, EventReasonUnauthorized)
			expectNoEvent()
		})
	})

	Context("HTTP 403 — namespace mismatch between path and classifier", func() {
		It("sets Phase=InvalidConfiguration, emits Warning/AggregatorRejected event, does not requeue", func() {
			mockStatusCode = http.StatusForbidden
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("AggregatorRejected"))
			expectEvent(corev1.EventTypeWarning, EventReasonAggregatorRejected)
			expectNoEvent()
		})
	})

	Context("HTTP 409 — database exists as internal (not externally manageable)", func() {
		It("sets Phase=InvalidConfiguration, emits Warning/AggregatorRejected event, does not requeue", func() {
			mockStatusCode = http.StatusConflict
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("AggregatorRejected"))
			expectEvent(corev1.EventTypeWarning, EventReasonAggregatorRejected)
			expectNoEvent()
		})
	})

	// ── Aggregator 5xx / network errors ──────────────────────────────────────

	Context("HTTP 500 — unexpected aggregator server error", func() {
		It("sets Phase=BackingOff and AggregatorError condition, emits Warning/AggregatorError event, requeues", func() {
			mockStatusCode = http.StatusInternalServerError
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("AggregatorError"))
			expectEvent(corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoEvent()
		})
	})

	Context("Network error — aggregator is unreachable", func() {
		It("sets Phase=BackingOff and AggregatorError condition, emits Warning/AggregatorError event, requeues", func() {
			// Close the server before reconcile so the HTTP call fails with a
			// connection-refused / EOF error (no AggregatorError wrapping).
			mockServer.Close()

			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			cond := findCondition(edb.Status.Conditions, conditionTypeRegistered)
			Expect(cond).NotTo(BeNil())
			Expect(cond.Status).To(Equal(metav1.ConditionFalse))
			Expect(cond.Reason).To(Equal("AggregatorError"))
			expectEvent(corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoEvent()
		})
	})
})
