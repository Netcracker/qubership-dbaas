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
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
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

const (
	statusCompleted     = `{"status":"COMPLETED"}`
	testToken           = "test-token"
	body401Unauthorized = `{"message":"Requested role is not allowed","status":"401","@type":"NC.TMFErrorResponse.v1.0"}`
)

var _ = Describe("DatabaseDeclaration Controller", func() {
	const (
		ns           = "default"
		resourceName = "test-dd"
	)

	var (
		applyCode         int
		applyBody         string
		pollCode          int
		pollBody          string
		capturedApplyBody []byte
		mockServer        *httptest.Server
		reconciler        *DatabaseDeclarationReconciler
		fakeRecorder      *record.FakeRecorder
		namespacedName    types.NamespacedName
	)

	// baseSpec returns a minimal valid DatabaseDeclaration spec.
	baseSpec := func() dbaasv1alpha1.DatabaseDeclarationSpec {
		return dbaasv1alpha1.DatabaseDeclarationSpec{
			Classifier: dbaasv1alpha1.Classifier{
				MicroserviceName: "test-service",
				Scope:            "service",
			},
			Type: "postgresql",
		}
	}

	BeforeEach(func() {
		applyCode = http.StatusOK
		applyBody = statusCompleted
		pollCode = http.StatusOK
		pollBody = statusCompleted
		capturedApplyBody = nil

		mockServer = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")

			if r.Method == http.MethodPost && r.URL.Path == "/api/declarations/v1/apply" {
				capturedApplyBody, _ = io.ReadAll(r.Body)
				w.WriteHeader(applyCode)
				if applyBody != "" {
					_, _ = w.Write([]byte(applyBody))
				}
				return
			}

			if r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/operation/") {
				w.WriteHeader(pollCode)
				if pollBody != "" {
					_, _ = w.Write([]byte(pollBody))
				}
				return
			}

			w.WriteHeader(http.StatusNotFound)
		}))

		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}
		fakeRecorder = record.NewFakeRecorder(16)
		reconciler = &DatabaseDeclarationReconciler{
			Client:     k8sClient,
			Scheme:     k8sClient.Scheme(),
			Aggregator: aggregatorclient.NewClientWithTokenFunc(mockServer.URL, func(_ context.Context) (string, error) { return testToken, nil }),
			Recorder:   fakeRecorder,
			Ownership:  mineOwnershipResolver(ns),
		}
	})

	AfterEach(func() {
		mockServer.Close()

		dd := &dbaasv1alpha1.DatabaseDeclaration{}
		if err := k8sClient.Get(ctx, namespacedName, dd); err == nil {
			Expect(k8sClient.Delete(ctx, dd)).To(Succeed())
		}

		drainRecordedEvents(fakeRecorder.Events)
	})

	reconcileAndFetch := func() (*dbaasv1alpha1.DatabaseDeclaration, reconcile.Result, error) {
		GinkgoHelper()
		return reconcileAndFetchObject(reconciler, namespacedName, func() *dbaasv1alpha1.DatabaseDeclaration {
			return &dbaasv1alpha1.DatabaseDeclaration{}
		})
	}

	// ── CRD admission validation ──────────────────────────────────────────────

	Context("classifier.microserviceName is empty", func() {
		It("is rejected by CRD admission before reaching the controller", func() {
			spec := baseSpec()
			spec.Classifier.MicroserviceName = ""
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("microserviceName"))
			Expect(capturedApplyBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	Context("classifier.scope is empty", func() {
		It("is rejected by CRD admission before reaching the controller", func() {
			spec := baseSpec()
			spec.Classifier.Scope = ""
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("scope"))
			Expect(capturedApplyBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	Context("type is empty", func() {
		It("is rejected by CRD admission before reaching the controller", func() {
			spec := baseSpec()
			spec.Type = ""
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("type"))
			Expect(capturedApplyBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	// ── Controller pre-flight checks ──────────────────────────────────────────

	Context("lazy=true with approach=clone", func() {
		It("sets Phase=InvalidConfiguration without calling the aggregator", func() {
			spec := baseSpec()
			spec.Lazy = true
			spec.InitialInstantiation = &dbaasv1alpha1.InitialInstantiation{
				Approach: "clone",
				SourceClassifier: &dbaasv1alpha1.Classifier{
					MicroserviceName: "test-service",
					Scope:            "service",
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(capturedApplyBody).To(BeEmpty(), "aggregator must not be called")

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring("lazy=true"))
			Expect(ready.Message).To(ContainSubstring("clone"))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("approach=clone without sourceClassifier", func() {
		It("sets Phase=InvalidConfiguration without calling the aggregator", func() {
			spec := baseSpec()
			spec.InitialInstantiation = &dbaasv1alpha1.InitialInstantiation{
				Approach: "clone",
				// SourceClassifier deliberately omitted
			}
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(capturedApplyBody).To(BeEmpty())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring("sourceClassifier"))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("sourceClassifier.microserviceName mismatch", func() {
		It("sets Phase=InvalidConfiguration without calling the aggregator", func() {
			spec := baseSpec()
			spec.InitialInstantiation = &dbaasv1alpha1.InitialInstantiation{
				Approach: "clone",
				SourceClassifier: &dbaasv1alpha1.Classifier{
					MicroserviceName: "other-service", // mismatch
					Scope:            "service",
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(capturedApplyBody).To(BeEmpty())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring("microserviceName"))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("classifier.namespace absent — omitted field, no validation error", func() {
		It("proceeds normally; aggregator receives namespace from metadata", func() {
			spec := baseSpec()
			// Namespace field left unset (zero value / omitempty).
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			dd, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(capturedApplyBody).NotTo(BeEmpty())
		})
	})

	Context("classifier.namespace matches metadata.namespace", func() {
		It("proceeds normally and succeeds", func() {
			spec := baseSpec()
			spec.Classifier.Namespace = ns // same as metadata.namespace
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
		})
	})

	Context("classifier.namespace does not match metadata.namespace", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/InvalidSpec, Stalled=True, does not requeue, never calls aggregator", func() {
			spec := baseSpec()
			spec.Classifier.Namespace = "other-namespace"
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(capturedApplyBody).To(BeEmpty(), "aggregator must not be called")

			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(dd.Status.ObservedGeneration).To(Equal(dd.Generation))

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring(`"other-namespace"`))
			Expect(ready.Message).To(ContainSubstring(ns))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── buildPayload ──────────────────────────────────────────────────────────

	Context("buildPayload", func() {
		It("sets kind=DBaaS, subKind=DatabaseDeclaration, microserviceName in metadata", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(capturedApplyBody).NotTo(BeEmpty())

			var sent struct {
				Kind     string `json:"kind"`
				SubKind  string `json:"subKind"`
				Metadata struct {
					MicroserviceName string `json:"microserviceName"`
					Namespace        string `json:"namespace"`
				} `json:"metadata"`
				Spec struct {
					Classifier struct {
						MicroserviceName string `json:"microserviceName"`
					} `json:"classifier"`
				} `json:"spec"`
			}
			Expect(json.Unmarshal(capturedApplyBody, &sent)).To(Succeed())
			Expect(sent.Kind).To(Equal("DBaaS"))
			Expect(sent.SubKind).To(Equal("DatabaseDeclaration"))
			Expect(sent.Metadata.MicroserviceName).To(Equal("test-service"))
			Expect(sent.Metadata.Namespace).To(Equal(ns))
			Expect(sent.Spec.Classifier.MicroserviceName).To(Equal("test-service"))
		})
	})

	// ── HTTP 200 — synchronous success ───────────────────────────────────────

	Context("HTTP 200 — database provisioned synchronously", func() {
		It("sets Phase=Succeeded, Ready=True, Stalled=False, emits Normal/DatabaseProvisioned, does not requeue", func() {
			applyCode = http.StatusOK
			applyBody = statusCompleted
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(dd.Status.ObservedGeneration).To(Equal(dd.Generation))
			Expect(dd.Status.TrackingID).To(BeEmpty())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonDatabaseProvisioned))
			Expect(ready.ObservedGeneration).To(Equal(dd.Generation))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(ReasonSucceeded))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeNormal, EventReasonDatabaseProvisioned)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── HTTP 202 — async provisioning started ─────────────────────────────────

	Context("HTTP 202 — async provisioning started", func() {
		It("sets Phase=WaitingForDependency, stores trackingId, emits ProvisioningStarted, requeues after poll interval", func() {
			applyCode = http.StatusAccepted
			applyBody = `{"trackingId":"track-abc-123"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter))
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseWaitingForDependency))
			Expect(dd.Status.TrackingID).To(Equal("track-abc-123"))
			Expect(dd.Status.PendingOperationGeneration).To(Equal(dd.Generation))
			Expect(dd.Status.ObservedGeneration).To(BeZero(),
				"observedGeneration must not be stamped while waiting for dependency")

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonProvisioningStarted))
			Expect(ready.Message).To(Equal("database provisioning started asynchronously"))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fakeRecorder.Events, corev1.EventTypeNormal, EventReasonProvisioningStarted, "track-abc-123")
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── POLL — COMPLETED ──────────────────────────────────────────────────────

	Context("POLL — status=COMPLETED", func() {
		It("sets Phase=Succeeded, clears trackingId, emits DatabaseProvisioned, does not requeue", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-poll-completed"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusOK
			pollBody = statusCompleted

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(dd.Status.TrackingID).To(BeEmpty())
			Expect(dd.Status.PendingOperationGeneration).To(BeZero())
			Expect(dd.Status.ObservedGeneration).To(Equal(dd.Generation))

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonDatabaseProvisioned))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeNormal, EventReasonDatabaseProvisioned)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── POLL — FAILED ─────────────────────────────────────────────────────────

	Context("POLL — status=FAILED", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, clears trackingId, emits AggregatorRejected, does not requeue", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-poll-failed"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusOK
			pollBody = `{"status":"FAILED","conditions":[{"type":"DataBaseCreated","state":"False","reason":"disk quota exceeded"}]}`

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(dd.Status.TrackingID).To(BeEmpty())
			Expect(dd.Status.PendingOperationGeneration).To(BeZero())
			Expect(dd.Status.ObservedGeneration).To(Equal(dd.Generation))

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))
			Expect(ready.Message).To(ContainSubstring("disk quota exceeded"))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEventContaining(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected, "disk quota exceeded")
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── POLL — TERMINATED ─────────────────────────────────────────────────────

	Context("POLL — status=TERMINATED", func() {
		It("sets Phase=BackingOff, Stalled=False, clears trackingId, emits OperationTerminated, requeues", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-terminated"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusOK
			pollBody = `{"status":"TERMINATED"}`

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			// Transient — requeues automatically.
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter))
			// BackingOff (not InvalidConfiguration) so the operator keeps retrying.
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			// trackingID cleared so the next reconcile enters the SUBMIT branch.
			Expect(dd.Status.TrackingID).To(BeEmpty())
			Expect(dd.Status.PendingOperationGeneration).To(BeZero())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonOperationTerminated))
			Expect(ready.Message).To(ContainSubstring("resubmitting"))

			// Stalled=False: this is transient, not a permanent spec error.
			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonOperationTerminated, "track-terminated")
			expectNoRecordedEvent(fakeRecorder.Events)
		})

		It("resubmits to the aggregator on the next reconcile after TERMINATED", func() {
			applyCode = http.StatusAccepted
			applyBody = `{"trackingId":"track-resubmit"}`

			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			// Simulate a pending tracking ID that comes back TERMINATED.
			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-stale"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusOK
			pollBody = `{"status":"TERMINATED"}`

			// First reconcile: TERMINATED → BackingOff, trackingID cleared.
			dd, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(dd.Status.TrackingID).To(BeEmpty())
			Expect(capturedApplyBody).To(BeEmpty(), "apply must not be called during poll reconcile")
			drainRecordedEvents(fakeRecorder.Events)

			// Second reconcile: trackingID is empty → SUBMIT branch → POST /apply.
			pollBody = `{"status":"IN_PROGRESS"}`
			dd, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter))
			Expect(capturedApplyBody).NotTo(BeEmpty(), "apply must be called on resubmit")
			Expect(dd.Status.TrackingID).To(Equal("track-resubmit"))
		})
	})

	// ── POLL — IN_PROGRESS ────────────────────────────────────────────────────

	Context("POLL — status=IN_PROGRESS", func() {
		It("keeps Phase=WaitingForDependency, keeps trackingId, requeues after poll interval", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			gen := dd.Generation
			dd.Status.TrackingID = "track-in-progress"
			dd.Status.PendingOperationGeneration = gen
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusOK
			pollBody = `{"status":"IN_PROGRESS"}`

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter))
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseWaitingForDependency))
			Expect(dd.Status.TrackingID).To(Equal("track-in-progress"))
			Expect(dd.Status.ObservedGeneration).To(BeZero(),
				"observedGeneration must not be stamped while in progress")

			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── POLL — HTTP 404 ───────────────────────────────────────────────────────

	Context("POLL — HTTP 404 trackingId not found", func() {
		It("clears trackingId, sets Phase=BackingOff, Stalled=False, requeues", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-gone"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusNotFound
			pollBody = ""

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dd.Status.TrackingID).To(BeEmpty(),
				"trackingId must be cleared on 404 so next reconcile re-submits")
			Expect(dd.Status.PendingOperationGeneration).To(BeZero())

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── POLL — HTTP 401 ───────────────────────────────────────────────────────

	Context("POLL — HTTP 401 unauthorized", func() {
		It("keeps trackingId, sets Phase=BackingOff, Stalled=False, requeues", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-unauth"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusUnauthorized
			pollBody = `{"message":"Unauthorized","status":"401","@type":"NC.TMFErrorResponse.v1.0"}`

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dd.Status.TrackingID).To(Equal("track-unauth"),
				"trackingId must be retained on 401 to resume polling after credentials are fixed")

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonUnauthorized))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonUnauthorized)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── POLL — HTTP 500 ───────────────────────────────────────────────────────

	Context("POLL — HTTP 500 server error", func() {
		It("keeps trackingId, sets Phase=BackingOff, Stalled=False, requeues", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			dd.Status.TrackingID = "track-500"
			dd.Status.PendingOperationGeneration = dd.Generation
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			pollCode = http.StatusInternalServerError
			pollBody = `{"code":"CORE-DBAAS-2000","message":"Unexpected exception","status":"500","@type":"NC.TMFErrorResponse.v1.0"}`

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dd.Status.TrackingID).To(Equal("track-500"),
				"trackingId must be retained on 5xx to resume polling after the aggregator recovers")

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── SUBMIT errors ─────────────────────────────────────────────────────────

	Context("SUBMIT — HTTP 400 bad request", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, does not requeue", func() {
			applyCode = http.StatusBadRequest
			applyBody = `{"code":"CORE-DBAAS-4036","reason":"Validation failed","message":"Declarative configuration validation failed.","status":"400","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(dd.Status.ObservedGeneration).To(Equal(dd.Generation))

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))
			Expect(ready.Message).To(ContainSubstring("Declarative configuration validation failed."))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEventContaining(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Declarative configuration validation failed.")
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("SUBMIT — HTTP 401 unauthorized", func() {
		It("sets Phase=BackingOff, Stalled=False, requeues", func() {
			applyCode = http.StatusUnauthorized
			applyBody = body401Unauthorized
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dd.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonUnauthorized))
			Expect(ready.Message).To(ContainSubstring("Requested role is not allowed"))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonUnauthorized,
				"Requested role is not allowed")
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("SUBMIT — HTTP 404 not found (infrastructure error)", func() {
		It("sets Phase=BackingOff (not InvalidConfiguration), Stalled=False, requeues", func() {
			applyCode = http.StatusNotFound
			applyBody = ""
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff),
				"404 is an infrastructure error, not a spec rejection; CR must not be stuck in InvalidConfiguration")
			Expect(dd.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("SUBMIT — HTTP 429 too many requests (rate limit)", func() {
		It("sets Phase=BackingOff (not InvalidConfiguration), Stalled=False, requeues", func() {
			applyCode = http.StatusTooManyRequests
			applyBody = ""
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff),
				"429 is a transient rate limit, not a spec rejection; CR must not be stuck in InvalidConfiguration")

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("SUBMIT — HTTP 500 server error", func() {
		It("sets Phase=BackingOff, Stalled=False, requeues", func() {
			applyCode = http.StatusInternalServerError
			applyBody = `{"code":"CORE-DBAAS-2000","message":"Unexpected exception","status":"500","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dd.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))
			Expect(ready.Message).To(ContainSubstring("Unexpected exception"))

			stalled := findCondition(dd.Status.Conditions, conditionTypeStalled)
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError, "Unexpected exception")
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	Context("SUBMIT — network error (aggregator unreachable)", func() {
		It("sets Phase=BackingOff, Stalled=False, requeues", func() {
			mockServer.Close()
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))

			ready := findCondition(dd.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})

	// ── Spec change during polling ────────────────────────────────────────────

	Context("spec change detected while an async operation is in progress", func() {
		It("clears the stale trackingId and re-submits to the aggregator", func() {
			applyCode = http.StatusOK
			applyBody = statusCompleted

			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DatabaseDeclaration{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dd := &dbaasv1alpha1.DatabaseDeclaration{}
			Expect(k8sClient.Get(ctx, namespacedName, dd)).To(Succeed())
			// Simulate a stale trackingId from a previous generation.
			dd.Status.TrackingID = "track-stale"
			dd.Status.PendingOperationGeneration = dd.Generation - 1 // mismatch
			dd.Status.Phase = dbaasv1alpha1.PhaseWaitingForDependency
			Expect(k8sClient.Status().Update(ctx, dd)).To(Succeed())

			dd, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			// Stale trackingId cleared → took the SUBMIT branch → sync 200 → Succeeded.
			Expect(dd.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(dd.Status.TrackingID).To(BeEmpty())
			Expect(result.RequeueAfter).To(BeZero())
			// The apply endpoint must have been called (not the poll endpoint).
			Expect(capturedApplyBody).NotTo(BeEmpty())

			expectRecordedEvent(fakeRecorder.Events, corev1.EventTypeNormal, EventReasonDatabaseProvisioned)
			expectNoRecordedEvent(fakeRecorder.Events)
		})
	})
})

// ── Rate limiter / SetupWithManager ───────────────────────────────────────────

var _ = Describe("DatabaseDeclaration Controller — rate limiter", func() {
	It("registers the controller with a custom exponential rate limiter", func() {
		mgr, err := ctrl.NewManager(cfg, ctrl.Options{
			Scheme:                 k8sClient.Scheme(),
			Metrics:                metricsserver.Options{BindAddress: "0"},
			HealthProbeBindAddress: "0",
		})
		Expect(err).NotTo(HaveOccurred())

		const base = 100 * time.Millisecond
		const max = 10 * time.Second

		rateLimiter := workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](base, max)

		err = (&DatabaseDeclarationReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Recorder:   mgr.GetEventRecorderFor("dd-rate-limiter-test"), // nolint:staticcheck
			Aggregator: aggregatorclient.NewClientWithTokenFunc("http://localhost:9999", func(_ context.Context) (string, error) { return testToken, nil }),
			Ownership:  mineOwnershipResolver("ns"),
		}).SetupWithManager(mgr, ctrlcontroller.Options{RateLimiter: rateLimiter})
		Expect(err).NotTo(HaveOccurred())

		req := reconcile.Request{NamespacedName: types.NamespacedName{Name: "dd", Namespace: "ns"}}
		Expect(rateLimiter.When(req)).To(Equal(base))
		Expect(rateLimiter.When(req)).To(Equal(2 * base))
		Expect(rateLimiter.When(req)).To(Equal(4 * base))

		rateLimiter.Forget(req)
		Expect(rateLimiter.When(req)).To(Equal(base))
	})
})

// ── pollConditionText / pollFailureReason / pollProgressMessage ───────────────

var _ = Describe("pollConditionText helpers", func() {
	Context("DataBaseCreated condition — message preferred over reason", func() {
		It("returns message when both message and reason are set", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status: aggregatorclient.TaskStateFailed,
				Conditions: []aggregatorclient.AggregatorCondition{
					{Type: "DataBaseCreated", State: "False",
						Reason: "machine-code", Message: "human-readable failure detail"},
				},
			}
			Expect(pollFailureReason(resp)).To(Equal("human-readable failure detail"))
		})

		It("returns reason when message is empty", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status: aggregatorclient.TaskStateFailed,
				Conditions: []aggregatorclient.AggregatorCondition{
					{Type: "DataBaseCreated", State: "False", Reason: "out of disk space"},
				},
			}
			Expect(pollFailureReason(resp)).To(Equal("out of disk space"))
		})
	})

	Context("no DataBaseCreated condition — falls back to other condition types", func() {
		It("returns message from another condition when DataBaseCreated is absent", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status: aggregatorclient.TaskStateFailed,
				Conditions: []aggregatorclient.AggregatorCondition{
					{Type: "Validated", State: "COMPLETED"},
					{Type: "OperatorCheck", State: "False",
						Message: "quota exceeded on the storage backend"},
				},
			}
			Expect(pollFailureReason(resp)).To(Equal("quota exceeded on the storage backend"))
		})

		It("returns reason from another condition when no message is available", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status: aggregatorclient.TaskStateFailed,
				Conditions: []aggregatorclient.AggregatorCondition{
					{Type: "OtherCheck", Reason: "StorageFull"},
				},
			}
			Expect(pollFailureReason(resp)).To(Equal("StorageFull"))
		})

		It("falls back to formatted status string when no conditions carry useful text", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status:     aggregatorclient.TaskStateFailed,
				Conditions: []aggregatorclient.AggregatorCondition{},
			}
			Expect(pollFailureReason(resp)).To(ContainSubstring(
				string(aggregatorclient.TaskStateFailed)))
		})
	})

	Context("pollProgressMessage", func() {
		It("returns empty string when no useful condition text is available", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status:     aggregatorclient.TaskStateInProgress,
				Conditions: []aggregatorclient.AggregatorCondition{},
			}
			Expect(pollProgressMessage(resp)).To(BeEmpty())
		})

		It("returns DataBaseCreated message when present", func() {
			resp := &aggregatorclient.DeclarativeResponse{
				Status: aggregatorclient.TaskStateInProgress,
				Conditions: []aggregatorclient.AggregatorCondition{
					{Type: "DataBaseCreated", State: "IN_PROGRESS",
						Message: "waiting for cluster capacity"},
				},
			}
			Expect(pollProgressMessage(resp)).To(Equal("waiting for cluster capacity"))
		})
	})
})
