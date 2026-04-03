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
	"net/http"
	"time"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	metricsserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1alpha1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1alpha1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

var _ = Describe("DbPolicy Controller", func() {
	const (
		ns           = "default"
		resourceName = "test-dbpolicy"
	)

	var (
		fixture        *aggregatorSyncFixture
		reconciler     *DbPolicyReconciler
		namespacedName types.NamespacedName
	)

	// baseSpec builds a minimal valid spec for use in aggregator-response tests.
	baseSpec := func() dbaasv1alpha1.DbPolicySpec {
		return dbaasv1alpha1.DbPolicySpec{
			MicroserviceName: "test-service",
			Services: []dbaasv1alpha1.ServiceRole{
				{Name: "other-service", Roles: []string{"admin"}},
			},
		}
	}

	BeforeEach(func() {
		fixture = newAggregatorSyncFixture()
		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}
		reconciler = &DbPolicyReconciler{
			Client:     k8sClient,
			Scheme:     k8sClient.Scheme(),
			Aggregator: aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(_ context.Context) (string, error) { return "test-token", nil }),
			Recorder:   fixture.recorder,
		}
	})

	AfterEach(func() {
		fixture.close()
		deleteIfExists(&dbaasv1alpha1.DbPolicy{ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns}})
	})

	reconcileAndFetch := func() (*dbaasv1alpha1.DbPolicy, reconcile.Result, error) {
		return reconcileAndFetchObject(reconciler, namespacedName, func() *dbaasv1alpha1.DbPolicy {
			return &dbaasv1alpha1.DbPolicy{}
		})
	}

	// ── Request payload assembly ──────────────────────────────────────────────

	Context("buildPayload — microserviceName goes to metadata, not spec", func() {
		It("serializes microserviceName in metadata and excludes it from spec", func() {
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedBody).NotTo(BeEmpty())

			var sent struct {
				Metadata struct {
					MicroserviceName string `json:"microserviceName"`
				} `json:"metadata"`
				Spec struct {
					MicroserviceName string `json:"microserviceName"`
				} `json:"spec"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())
			Expect(sent.Metadata.MicroserviceName).To(Equal("test-service"))
			Expect(sent.Spec.MicroserviceName).To(BeEmpty(),
				"microserviceName must not appear in spec")
		})
	})

	Context("buildPayload — services and policy are serialized into spec", func() {
		It("sends services and policy in the spec field", func() {
			spec := dbaasv1alpha1.DbPolicySpec{
				MicroserviceName: "test-service",
				Services: []dbaasv1alpha1.ServiceRole{
					{Name: "other-svc", Roles: []string{"admin", "readonly"}},
				},
				Policy: []dbaasv1alpha1.PolicyRole{
					{Type: "postgresql", DefaultRole: "admin", AdditionalRole: []string{"readonly"}},
				},
			}
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())

			var sent struct {
				Spec struct {
					Services []struct {
						Name  string   `json:"name"`
						Roles []string `json:"roles"`
					} `json:"services"`
					Policy []struct {
						Type           string   `json:"type"`
						DefaultRole    string   `json:"defaultRole"`
						AdditionalRole []string `json:"additionalRole"`
					} `json:"policy"`
				} `json:"spec"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())
			Expect(sent.Spec.Services).To(HaveLen(1))
			Expect(sent.Spec.Services[0].Name).To(Equal("other-svc"))
			Expect(sent.Spec.Services[0].Roles).To(ConsistOf("admin", "readonly"))
			Expect(sent.Spec.Policy).To(HaveLen(1))
			Expect(sent.Spec.Policy[0].Type).To(Equal("postgresql"))
			Expect(sent.Spec.Policy[0].DefaultRole).To(Equal("admin"))
		})
	})

	// ── Pre-flight validation ─────────────────────────────────────────────────

	Context("microserviceName is empty", func() {
		It("is rejected by CRD admission validation before reaching the controller", func() {
			spec := baseSpec()
			spec.MicroserviceName = ""
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("microserviceName"))
			Expect(fixture.capturedBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	Context("both services and policy are empty", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/InvalidSpec, Stalled=True, does not requeue", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       dbaasv1alpha1.DbPolicySpec{MicroserviceName: "test-service"},
			})).To(Succeed())

			dp, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(fixture.capturedBody).To(BeEmpty())

			ready := findCondition(dp.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring("at least one of"))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("services entry has empty name", func() {
		It("is rejected by CRD admission validation before reaching the controller", func() {
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec: dbaasv1alpha1.DbPolicySpec{
					MicroserviceName: "test-service",
					Services:         []dbaasv1alpha1.ServiceRole{{Name: "", Roles: []string{"admin"}}},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("services"))
			Expect(fixture.capturedBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	Context("services entry has empty roles", func() {
		It("is rejected by CRD admission validation before reaching the controller", func() {
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec: dbaasv1alpha1.DbPolicySpec{
					MicroserviceName: "test-service",
					Services:         []dbaasv1alpha1.ServiceRole{{Name: "other-svc", Roles: nil}},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("roles"))
			Expect(fixture.capturedBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	Context("policy entry has empty type", func() {
		It("is rejected by CRD admission validation before reaching the controller", func() {
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec: dbaasv1alpha1.DbPolicySpec{
					MicroserviceName: "test-service",
					Policy:           []dbaasv1alpha1.PolicyRole{{Type: "", DefaultRole: "admin"}},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("type"))
			Expect(fixture.capturedBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	Context("policy entry has empty defaultRole", func() {
		It("is rejected by CRD admission validation before reaching the controller", func() {
			err := k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec: dbaasv1alpha1.DbPolicySpec{
					MicroserviceName: "test-service",
					Policy:           []dbaasv1alpha1.PolicyRole{{Type: "postgresql", DefaultRole: ""}},
				},
			})
			Expect(err).To(HaveOccurred())
			Expect(err.Error()).To(ContainSubstring("defaultRole"))
			Expect(fixture.capturedBody).To(BeEmpty(), "aggregator must not be called")
		})
	})

	// ── Success cases ─────────────────────────────────────────────────────────

	Context("HTTP 200 — policy applied synchronously", func() {
		It("sets Phase=Succeeded, Ready=True, Stalled=False, emits Normal/PolicyApplied, does not requeue", func() {
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dp, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseSucceeded))
			Expect(dp.Status.ObservedGeneration).To(Equal(dp.Generation))

			ready := findCondition(dp.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonPolicyApplied))
			Expect(ready.ObservedGeneration).To(Equal(dp.Generation))

			stalled := findCondition(dp.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(ReasonSucceeded))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonPolicyApplied)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Aggregator error cases ────────────────────────────────────────────────

	Context("HTTP 400 — invalid request", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, does not requeue", func() {
			fixture.statusCode = http.StatusBadRequest
			fixture.body = `{"code":"CORE-DBAAS-4036","reason":"Validation failed","message":"Declarative configuration validation failed.","status":"400","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dp, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.Requeue).To(BeFalse())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseInvalidConfiguration))
			Expect(dp.Status.ObservedGeneration).To(Equal(dp.Generation))

			ready := findCondition(dp.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))
			Expect(ready.Message).To(ContainSubstring("Declarative configuration validation failed."))

			stalled := findCondition(dp.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Declarative configuration validation failed.")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 401 — operator credentials misconfigured", func() {
		It("sets Phase=BackingOff, Ready=False/Unauthorized, Stalled=False, requeues", func() {
			fixture.statusCode = http.StatusUnauthorized
			fixture.body = `{"message":"Requested role is not allowed","status":"401","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dp, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dp.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(dp.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonUnauthorized))
			Expect(ready.Message).To(ContainSubstring("Requested role is not allowed"))

			stalled := findCondition(dp.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonUnauthorized,
				"Requested role is not allowed")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 500 — unexpected aggregator server error", func() {
		It("sets Phase=BackingOff, Ready=False/AggregatorError, Stalled=False, requeues", func() {
			fixture.statusCode = http.StatusInternalServerError
			fixture.body = `{"code":"CORE-DBAAS-2000","reason":"Unexpected exception","message":"Unexpected exception","status":"500","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dp, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dp.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(dp.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))
			Expect(ready.Message).To(ContainSubstring("Unexpected exception"))

			stalled := findCondition(dp.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError, "Unexpected exception")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 404 — infrastructure error, not a spec rejection", func() {
		It("sets Phase=BackingOff (not InvalidConfiguration), Stalled=False, requeues", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = ""
			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dp, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff),
				"404 is an infrastructure error, not a spec rejection; CR must not be stuck in InvalidConfiguration")

			stalled := findCondition(dp.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Network error — aggregator is unreachable", func() {
		It("sets Phase=BackingOff, Ready=False/AggregatorError, Stalled=False, requeues", func() {
			fixture.server.Close()

			Expect(k8sClient.Create(ctx, &dbaasv1alpha1.DbPolicy{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			dp, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(dp.Status.Phase).To(Equal(dbaasv1alpha1.PhaseBackingOff))
			Expect(dp.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(dp.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			stalled := findCondition(dp.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})
})

// ── Rate limiter / SetupWithManager ───────────────────────────────────────────

var _ = Describe("DbPolicy Controller — rate limiter", func() {
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

		err = (&DbPolicyReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Recorder:   mgr.GetEventRecorderFor("dp-rate-limiter-test"),
			Aggregator: aggregatorclient.NewClientWithTokenFunc("http://localhost:9999", func(_ context.Context) (string, error) { return "test-token", nil }),
		}).SetupWithManager(mgr, ctrlcontroller.Options{RateLimiter: rateLimiter})
		Expect(err).NotTo(HaveOccurred())

		req := reconcile.Request{NamespacedName: types.NamespacedName{Name: "dp", Namespace: "ns"}}
		Expect(rateLimiter.When(req)).To(Equal(base))
		Expect(rateLimiter.When(req)).To(Equal(2 * base))
		Expect(rateLimiter.When(req)).To(Equal(4 * base))

		rateLimiter.Forget(req)
		Expect(rateLimiter.When(req)).To(Equal(base))
	})
})
