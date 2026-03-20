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
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"time"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
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
		mockServer          *httptest.Server
		mockStatusCode      int
		mockBody            string // optional TMF JSON body; written after the status code
		capturedRequestBody []byte // body of the last request received by the mock server
		capturedRequestPath string
		reconciler          *ExternalDatabaseDeclarationReconciler
		fakeRecorder        *record.FakeRecorder
		namespacedName      types.NamespacedName
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
				{
					Role: "admin",
					ExtraProperties: map[string]string{
						"url": "jdbc:postgresql://pg:5432/testdb",
					},
				},
			},
		}
	}

	BeforeEach(func() {
		mockStatusCode = http.StatusOK
		mockBody = ""
		capturedRequestBody = nil
		capturedRequestPath = ""
		mockServer = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			capturedRequestBody, _ = io.ReadAll(r.Body)
			capturedRequestPath = r.URL.Path
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(mockStatusCode)
			if mockBody != "" {
				_, _ = w.Write([]byte(mockBody))
			}
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
	// record.FakeRecorder writes events as: eventtype + " " + reason + " " + note
	// (see k8s.io/client-go/tools/record.FakeRecorder.Eventf).
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

	// expectEventContaining asserts that the next event starts with
	// "<eventtype> <reason>" AND contains substr somewhere in its text.
	// Use this when a TMF message should appear verbatim in the event body.
	expectEventContaining := func(eventtype, reason, substr string) {
		GinkgoHelper()
		Expect(fakeRecorder.Events).To(Receive(And(
			HavePrefix(eventtype+" "+reason),
			ContainSubstring(substr),
		)))
	}

	// ── Request assembly ──────────────────────────────────────────────────────

	Context("buildRequest — ExtraProperties vs typed fields priority", func() {
		It("typed fields take precedence over ExtraProperties with the same key", func() {
			spec := baseSpec()
			spec.ConnectionProperties = []dbaasv1alpha1.ConnectionProperty{
				{
					Role: "primary",
					// "role" in ExtraProperties must NOT win over the typed Role field.
					ExtraProperties: map[string]string{
						"role": "admin",
					},
				},
			}
			mockStatusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(capturedRequestBody).NotTo(BeEmpty())

			var sent struct {
				ConnectionProperties []map[string]string `json:"connectionProperties"`
			}
			Expect(json.Unmarshal(capturedRequestBody, &sent)).To(Succeed())
			Expect(sent.ConnectionProperties).To(HaveLen(1))

			props := sent.ConnectionProperties[0]
			Expect(props["role"]).To(Equal("primary"),
				"typed Role must override ExtraProperties role")
		})
	})

	Context("aggregator namespace resolution", func() {
		It("falls back to the CR namespace when classifier.namespace is absent", func() {
			spec := baseSpec()
			delete(spec.Classifier, "namespace")
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(capturedRequestPath).To(Equal("/api/v3/dbaas/" + ns + "/databases/registration/externally_manageable"))
		})
	})

	// ── Success cases ─────────────────────────────────────────────────────────

	Context("HTTP 200 — database already registered (no update)", func() {
		It("sets Phase=Succeeded, Ready=True, Stalled=False, emits Normal/Registered event, does not requeue", func() {
			mockStatusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonRegistered))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(ReasonSucceeded))

			expectEvent(corev1.EventTypeNormal, EventReasonRegistered)
			expectNoEvent()
		})
	})

	Context("HTTP 201 — database successfully created or updated", func() {
		It("sets Phase=Succeeded, Ready=True, Stalled=False, emits Normal/Registered event, does not requeue", func() {
			mockStatusCode = http.StatusCreated
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonRegistered))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(ReasonSucceeded))

			expectEvent(corev1.EventTypeNormal, EventReasonRegistered)
			expectNoEvent()
		})
	})

	// ── Secret resolution error ───────────────────────────────────────────────

	Context("Secret referenced in credentialsSecretRef does not exist", func() {
		It("sets Phase=BackingOff, Ready=False/SecretError, Stalled=False, emits Warning/SecretError event, requeues", func() {
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
			// Transient error: observedGeneration must NOT be stamped so that
			// consumers can tell the controller has not finished this generation.
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonSecretError))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectEvent(corev1.EventTypeWarning, EventReasonSecretError)
			expectNoEvent()
		})
	})

	Context("Secret exists but is missing the required key", func() {
		It("sets Phase=BackingOff, Ready=False/SecretError, Stalled=False, emits Warning/SecretError event, requeues", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					// Secret exists but does not contain "username" or "password".
					"irrelevant-key": []byte("irrelevant-value"),
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1alpha1.CredentialsSecretRef{
				Name: secretName,
			}
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonSecretError))
			Expect(ready.Message).To(ContainSubstring(`missing key "username"`))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectEvent(corev1.EventTypeWarning, EventReasonSecretError)
			expectNoEvent()
		})
	})

	// ── Aggregator 4xx errors ─────────────────────────────────────────────────

	Context("HTTP 400 — invalid request (e.g. invalid classifier)", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, emits Warning/AggregatorRejected, does not requeue", func() {
			mockStatusCode = http.StatusBadRequest
			mockBody = `{"code":"CORE-DBAAS-4010","reason":"Invalid classifier","message":"Invalid classifier. Classifier does not meet required conditions.","status":"400","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))
			Expect(ready.Message).To(ContainSubstring("Invalid classifier. Classifier does not meet required conditions."))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectEventContaining(corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Invalid classifier. Classifier does not meet required conditions.")
			expectNoEvent()
		})
	})

	Context("HTTP 401 — operator credentials or role binding misconfigured", func() {
		It("sets Phase=BackingOff, Ready=False/Unauthorized, Stalled=False, emits Warning/Unauthorized, requeues", func() {
			mockStatusCode = http.StatusUnauthorized
			mockBody = `{"message":"Requested role is not allowed","status":"401","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			// Transient error: observedGeneration must NOT be stamped.
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonUnauthorized))
			Expect(ready.Message).To(ContainSubstring("Requested role is not allowed"))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectEventContaining(corev1.EventTypeWarning, EventReasonUnauthorized,
				"Requested role is not allowed")
			expectNoEvent()
		})
	})

	Context("HTTP 403 — namespace mismatch between path and classifier", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, emits Warning/AggregatorRejected, does not requeue", func() {
			mockStatusCode = http.StatusForbidden
			mockBody = `{"code":"CORE-DBAAS-4004","reason":"Namespace from request is not equal to one from database classifier","message":"Namespace from request is not equal to one from database classifier.","status":"403","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))
			Expect(ready.Message).To(ContainSubstring("Namespace from request is not equal to one from database classifier."))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectEventContaining(corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Namespace from request is not equal to one from database classifier.")
			expectNoEvent()
		})
	})

	Context("HTTP 409 — database exists as internal (not externally manageable)", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, emits Warning/AggregatorRejected, does not requeue", func() {
			mockStatusCode = http.StatusConflict
			mockBody = `{"code":"CORE-DBAAS-4002","reason":"Conflict database request","message":"Conflict database request. Logical database already exists and is not externally manageable.","status":"409","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))
			Expect(ready.Message).To(ContainSubstring("Conflict database request. Logical database already exists and is not externally manageable."))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectEventContaining(corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Conflict database request. Logical database already exists and is not externally manageable.")
			expectNoEvent()
		})
	})

	// ── Aggregator 5xx / network errors ──────────────────────────────────────

	Context("HTTP 500 — unexpected aggregator server error", func() {
		It("sets Phase=BackingOff, Ready=False/AggregatorError, Stalled=False, emits Warning/AggregatorError, requeues", func() {
			mockStatusCode = http.StatusInternalServerError
			mockBody = `{"code":"CORE-DBAAS-2000","reason":"Unexpected exception","message":"Unexpected exception","status":"500","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			// Transient error: observedGeneration must NOT be stamped.
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))
			Expect(ready.Message).To(ContainSubstring("Unexpected exception"))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectEventContaining(corev1.EventTypeWarning, EventReasonAggregatorError, "Unexpected exception")
			expectNoEvent()
		})
	})

	Context("HTTP 404 — aggregator endpoint mismatch", func() {
		It("treats the response as transient and requeues instead of marking the spec invalid", func() {
			mockStatusCode = http.StatusNotFound
			mockBody = `{"message":"endpoint not found"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.ExternalDatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(edb.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))
			Expect(ready.Message).To(ContainSubstring("endpoint not found"))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(EventReasonAggregatorError))

			expectEventContaining(corev1.EventTypeWarning, EventReasonAggregatorError, "endpoint not found")
			expectNoEvent()
		})
	})

	Context("Network error — aggregator is unreachable", func() {
		It("sets Phase=BackingOff, Ready=False/AggregatorError, Stalled=False, emits Warning/AggregatorError, requeues", func() {
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
			// Transient error: observedGeneration must NOT be stamped.
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectEvent(corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoEvent()
		})
	})
})

// ── Rate limiter / SetupWithManager ───────────────────────────────────────────

var _ = Describe("ExternalDatabaseDeclaration Controller — rate limiter", func() {
	// SetupWithManager is not exercised in the reconcile-focused tests above
	// (those call Reconcile directly, bypassing the controller machinery).
	// This suite verifies two things:
	//
	//  1. SetupWithManager accepts a custom controller.Options without error,
	//     which confirms that our WithOptions wiring compiles and the manager
	//     accepts the registration.
	//
	//  2. A rate limiter created with the parameters used by --backoff-base-delay
	//     and --backoff-max-delay exhibits true exponential doubling.  This serves
	//     as a living spec for the BackingOff retry behaviour visible to operators.

	It("registers the controller with a custom exponential rate limiter", func() {
		// Create a throw-away manager backed by the same envtest API server.
		// Metrics and health probes are disabled to avoid port conflicts.
		mgr, err := ctrl.NewManager(cfg, ctrl.Options{
			Scheme:                 k8sClient.Scheme(),
			Metrics:                metricsserver.Options{BindAddress: "0"},
			HealthProbeBindAddress: "0",
		})
		Expect(err).NotTo(HaveOccurred())

		const base = 100 * time.Millisecond
		const max = 10 * time.Second

		rateLimiter := workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](base, max)

		err = (&ExternalDatabaseDeclarationReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Recorder:   mgr.GetEventRecorderFor("edb-rate-limiter-test"),
			Aggregator: aggregatorclient.NewAggregatorClient("http://localhost:9999", "u", "p"),
		}).SetupWithManager(mgr, ctrlcontroller.Options{RateLimiter: rateLimiter})
		Expect(err).NotTo(HaveOccurred())

		// Verify the exponential doubling behaviour of the rate limiter we
		// injected.  This is the contract that BackingOff retries rely on:
		// each consecutive failure doubles the wait time up to --backoff-max-delay.
		//
		// workqueue.TypedRateLimiter.When increments the internal failure counter
		// on every call, so successive calls for the same item double the delay.
		req := reconcile.Request{NamespacedName: types.NamespacedName{Name: "edb", Namespace: "ns"}}

		Expect(rateLimiter.When(req)).To(Equal(base))     // 1st failure: base
		Expect(rateLimiter.When(req)).To(Equal(2 * base)) // 2nd failure: 2× base
		Expect(rateLimiter.When(req)).To(Equal(4 * base)) // 3rd failure: 4× base

		// After Forget the counter is reset; the next failure starts from base again.
		rateLimiter.Forget(req)
		Expect(rateLimiter.When(req)).To(Equal(base))
	})
})
