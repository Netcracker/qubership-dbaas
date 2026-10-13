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
	"net/http/httptest"
	"time"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

// adapterAnswer is the physical database description from the design document,
// served by the given physical database id.
func adapterAnswer(physicalDatabaseID string) string {
	return `{
		"physicalDatabaseId": "` + physicalDatabaseID + `",
		"type": "postgresql",
		"labels": {"clusterName": "postgres-core"},
		"apiVersions": {"specs": [{"specRootUrl": "/api", "major": 2, "minor": 1, "supportedMajors": [2]}]},
		"features": {"multiusers": true, "tls": true},
		"supportedRoles": ["admin", "rw", "ro"],
		"readOnlyHost": "pg-patroni-ro.postgres"
	}`
}

// adapterProbeFixture is a test dbaas adapter that answers the physical database
// probe with a configurable status and body.
type adapterProbeFixture struct {
	statusCode  int
	body        string
	calls       int
	gotPath     string
	gotUser     string
	gotPassword string
	server      *httptest.Server
}

func newAdapterProbeFixture(physicalDatabaseID string) *adapterProbeFixture {
	f := &adapterProbeFixture{statusCode: http.StatusOK, body: adapterAnswer(physicalDatabaseID)}
	f.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.calls++
		f.gotPath = r.URL.Path
		f.gotUser, f.gotPassword, _ = r.BasicAuth()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(f.statusCode)
		_, _ = w.Write([]byte(f.body))
	}))
	return f
}

var _ = Describe("PhysicalDatabase Controller", func() {
	const (
		ns                 = "default"
		resourceName       = "test-pd"
		siblingName        = "test-pd-2"
		secretName         = "test-pd-adapter-credentials"
		physicalDatabaseID = "core-postgresql"
		testResync         = 7 * time.Minute
		registrationPath   = "/api/v3/dbaas/postgresql/physical_databases/core-postgresql"
	)

	var (
		aggregator     *aggregatorSyncFixture
		adapter        *adapterProbeFixture
		reconciler     *PhysicalDatabaseReconciler
		namespacedName types.NamespacedName
	)

	validCredentials := func() map[string][]byte {
		return map[string][]byte{"username": []byte("dbaas-aggregator"), "password": []byte("adapter-secret")}
	}

	specFor := func(address string) dbaasv1.PhysicalDatabaseSpec {
		return dbaasv1.PhysicalDatabaseSpec{
			OperatorNamespace:    testOperatorNamespace,
			AdapterAddress:       address,
			CredentialsSecretRef: dbaasv1.PhysicalDatabaseCredentialsSecretRef{Name: secretName},
		}
	}

	baseSpec := func() dbaasv1.PhysicalDatabaseSpec {
		return specFor(adapter.server.URL)
	}

	createSecret := func(data map[string][]byte) {
		GinkgoHelper()
		Expect(k8sClient.Create(ctx, &corev1.Secret{
			ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
			Data:       data,
		})).To(Succeed())
		Eventually(func() error {
			return cacheClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, &corev1.Secret{})
		}).Should(Succeed())
	}

	createCR := func(name string, spec dbaasv1.PhysicalDatabaseSpec) *dbaasv1.PhysicalDatabase {
		GinkgoHelper()
		cr := &dbaasv1.PhysicalDatabase{
			ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns},
			Spec:       spec,
		}
		Expect(k8sClient.Create(ctx, cr)).To(Succeed())
		return cr
	}

	reconcileAndFetchNamed := func(key types.NamespacedName) (*dbaasv1.PhysicalDatabase, reconcile.Result, error) {
		return reconcileAndFetchObject(reconciler, key, func() *dbaasv1.PhysicalDatabase {
			return &dbaasv1.PhysicalDatabase{}
		})
	}

	reconcileAndFetch := func() (*dbaasv1.PhysicalDatabase, reconcile.Result, error) {
		return reconcileAndFetchNamed(namespacedName)
	}

	expectState := func(p *dbaasv1.PhysicalDatabase, phase dbaasv1.Phase,
		ready metav1.ConditionStatus, reason string, stalled metav1.ConditionStatus) {
		GinkgoHelper()
		Expect(p.Status.Phase).To(Equal(phase))
		readyCond := findCondition(p.Status.Conditions, conditionTypeReady)
		Expect(readyCond).NotTo(BeNil())
		Expect(readyCond.Status).To(Equal(ready))
		Expect(readyCond.Reason).To(Equal(reason))
		stalledCond := findCondition(p.Status.Conditions, conditionTypeStalled)
		Expect(stalledCond).NotTo(BeNil())
		Expect(stalledCond.Status).To(Equal(stalled))
	}

	// olderAndYounger returns the keys of the two named resources ordered by the
	// older-claimant rule. Both are usually created within the same second, so
	// the UID decides.
	olderAndYounger := func() (types.NamespacedName, types.NamespacedName) {
		GinkgoHelper()
		first, second := &dbaasv1.PhysicalDatabase{}, &dbaasv1.PhysicalDatabase{}
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: resourceName, Namespace: ns}, first)).To(Succeed())
		Expect(k8sClient.Get(ctx, types.NamespacedName{Name: siblingName, Namespace: ns}, second)).To(Succeed())
		if isOlderClaimant(second, first) {
			return client.ObjectKeyFromObject(second), client.ObjectKeyFromObject(first)
		}
		return client.ObjectKeyFromObject(first), client.ObjectKeyFromObject(second)
	}

	expectIndexed := func(index, value string, count int) {
		GinkgoHelper()
		Eventually(func() (int, error) {
			list := &dbaasv1.PhysicalDatabaseList{}
			err := cacheClient.List(ctx, list, client.MatchingFields{index: value})
			return len(list.Items), err
		}).Should(Equal(count))
	}

	BeforeEach(func() {
		aggregator = newAggregatorSyncFixture()
		adapter = newAdapterProbeFixture(physicalDatabaseID)
		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}
		reconciler = &PhysicalDatabaseReconciler{
			Client:         cacheClient,
			Scheme:         cacheClient.Scheme(),
			Aggregator:     aggregatorclient.NewClientWithTokenFunc(aggregator.server.URL, func(context.Context) (string, error) { return testToken, nil }),
			Adapter:        aggregatorclient.NewAdapterClient(),
			Recorder:       aggregator.recorder,
			MyNamespace:    testOperatorNamespace,
			ResyncInterval: testResync,
		}
	})

	AfterEach(func() {
		aggregator.close()
		adapter.server.Close()
		deleteIfExists(&dbaasv1.PhysicalDatabase{ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns}})
		deleteIfExists(&dbaasv1.PhysicalDatabase{ObjectMeta: metav1.ObjectMeta{Name: siblingName, Namespace: ns}})
		deleteIfExists(&corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns}})
		// The reconciler reads through the cache: wait until it forgets this test's
		// objects so the next test neither lists a stale sibling nor reads a stale Secret.
		Eventually(func() (int, error) {
			list := &dbaasv1.PhysicalDatabaseList{}
			err := cacheClient.List(ctx, list, client.InNamespace(ns))
			return len(list.Items), err
		}).Should(BeZero())
		Eventually(func() bool {
			err := cacheClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, &corev1.Secret{})
			return apierrors.IsNotFound(err)
		}).Should(BeTrue())
	})

	// ── Successful registration ─────────────────────────────────────────────────

	Context("when the adapter answers and the aggregator answers 201", func() {
		It("reports Succeeded, mirrors the adapter answer in the status, and sends the wire request", func() {
			createSecret(validCredentials())
			createCR(resourceName, baseSpec())
			aggregator.statusCode = http.StatusCreated

			p, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(testResync))
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)
			Expect(p.Status.ObservedGeneration).To(Equal(p.Generation))
			Expect(p.Status.LastRequestID).NotTo(BeEmpty())
			expectRecordedEvent(aggregator.recorder.Events, corev1.EventTypeNormal, EventReasonPhysicalDatabaseRegistered)

			By("probing the adapter with the Secret's credentials")
			Expect(adapter.calls).To(Equal(1))
			Expect(adapter.gotPath).To(Equal("/api/v2/adapter/physical_database"))
			Expect(adapter.gotUser).To(Equal("dbaas-aggregator"))
			Expect(adapter.gotPassword).To(Equal("adapter-secret"))

			By("mirroring every field of the adapter answer in the status")
			Expect(p.Status.PhysicalDatabaseID).To(Equal(physicalDatabaseID))
			Expect(p.Status.Type).To(Equal("postgresql"))
			Expect(p.Status.Labels).To(Equal(map[string]string{"clusterName": "postgres-core"}))
			Expect(p.Status.SupportedRoles).To(Equal([]string{"admin", "rw", "ro"}))
			Expect(p.Status.Features).To(Equal(map[string]bool{"multiusers": true, "tls": true}))
			Expect(p.Status.ReadOnlyHost).To(Equal("pg-patroni-ro.postgres"))
			Expect(p.Status.APIVersions).To(Equal(&dbaasv1.AdapterAPIVersions{Specs: []dbaasv1.AdapterAPIVersionSpec{
				{SpecRootURL: "/api", Major: 2, Minor: 1, SupportedMajors: []int32{2}},
			}}))

			By("sending the spec address, the Secret's credentials, and the adapter answer to the aggregator")
			Expect(aggregator.capturedPath).To(Equal(registrationPath))
			var sent map[string]any
			Expect(json.Unmarshal(aggregator.capturedBody, &sent)).To(Succeed())
			Expect(sent).To(HaveKeyWithValue("adapterAddress", adapter.server.URL))
			Expect(sent).To(HaveKeyWithValue("status", "running"))
			Expect(sent).To(HaveKeyWithValue("labels", map[string]any{"clusterName": "postgres-core"}))
			Expect(sent).To(HaveKeyWithValue("httpBasicCredentials",
				map[string]any{"username": "dbaas-aggregator", "password": "adapter-secret"}))
			metadata, ok := sent["metadata"].(map[string]any)
			Expect(ok).To(BeTrue())
			Expect(metadata).To(HaveKeyWithValue("apiVersion", "v2"))
			Expect(metadata).To(HaveKeyWithValue("roHost", "pg-patroni-ro.postgres"))
			Expect(metadata).To(HaveKeyWithValue("supportedRoles", []any{"admin", "rw", "ro"}))
			Expect(metadata).To(HaveKeyWithValue("features", map[string]any{"multiusers": true, "tls": true}))
			Expect(metadata).To(HaveKeyWithValue("apiVersions", map[string]any{"specs": []any{map[string]any{
				"specRootUrl": "/api", "major": float64(2), "minor": float64(1),
				"supportedMajors": []any{float64(2)},
			}}}))
		})
	})

	Context("when the aggregator answers 200", func() {
		It("reports Succeeded", func() {
			createSecret(validCredentials())
			createCR(resourceName, baseSpec())
			aggregator.statusCode = http.StatusOK

			p, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)
		})
	})

	// ── Role migration ──────────────────────────────────────────────────────────

	Context("when the aggregator answers 202", func() {
		It("waits with RoleMigrationStarted, polls with a growing delay, then succeeds on a later 200", func() {
			createSecret(validCredentials())
			createCR(resourceName, baseSpec())
			aggregator.statusCode = http.StatusAccepted

			p, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			expectRequeueAfterStep(result, 0)
			expectState(p, dbaasv1.PhaseWaitingForDependency, metav1.ConditionFalse, ReasonRoleMigrationStarted, metav1.ConditionFalse)
			Expect(p.Status.ObservedGeneration).To(BeZero(), "a parked registration is not terminal")
			Expect(aggregator.capturedPath).To(Equal(registrationPath), "the operator must not call additional-roles")
			expectNoRecordedEvent(aggregator.recorder.Events)

			_, result, err = reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			expectRequeueAfterStep(result, 1)

			aggregator.reset()
			p, result, err = reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(testResync))
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)
			Expect(p.Status.ObservedGeneration).To(Equal(p.Generation))

			By("restarting the poll sequence after the success")
			aggregator.statusCode = http.StatusAccepted
			_, result, err = reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			expectRequeueAfterStep(result, 0)
		})
	})

	// ── Adapter probe ───────────────────────────────────────────────────────────

	Context("when the adapter probe fails", func() {
		DescribeTable("classifies the adapter response like an aggregator response and skips the registration",
			func(status int, body string, phase dbaasv1.Phase, reason string, stalled metav1.ConditionStatus, wantErr bool) {
				createSecret(validCredentials())
				createCR(resourceName, baseSpec())
				adapter.statusCode = status
				adapter.body = body

				p, result, err := reconcileAndFetch()
				if wantErr {
					Expect(err).To(HaveOccurred())
				} else {
					Expect(err).NotTo(HaveOccurred())
					Expect(result.RequeueAfter).To(BeZero())
				}
				expectState(p, phase, metav1.ConditionFalse, reason, stalled)
				Expect(p.Status.PhysicalDatabaseID).To(BeEmpty())
				Expect(aggregator.capturedPath).To(BeEmpty(), "the registration must not be sent")
				expectRecordedEvent(aggregator.recorder.Events, corev1.EventTypeWarning, reason)
			},
			Entry("401 backs off with Unauthorized", http.StatusUnauthorized, `{}`,
				dbaasv1.PhaseBackingOff, EventReasonUnauthorized, metav1.ConditionFalse, true),
			Entry("403 stalls with AdapterRejected", http.StatusForbidden, `{}`,
				dbaasv1.PhaseInvalidConfiguration, EventReasonAdapterRejected, metav1.ConditionTrue, false),
			Entry("409 stalls with AdapterRejected", http.StatusConflict, `{}`,
				dbaasv1.PhaseInvalidConfiguration, EventReasonAdapterRejected, metav1.ConditionTrue, false),
			Entry("404 backs off with AdapterError", http.StatusNotFound, `{}`,
				dbaasv1.PhaseBackingOff, EventReasonAdapterError, metav1.ConditionFalse, true),
			Entry("500 backs off with AdapterError", http.StatusInternalServerError, `{}`,
				dbaasv1.PhaseBackingOff, EventReasonAdapterError, metav1.ConditionFalse, true),
			Entry("200 without a type backs off with AdapterError", http.StatusOK,
				`{"physicalDatabaseId":"core-postgresql","supportedRoles":["admin"],"features":{"multiusers":true}}`,
				dbaasv1.PhaseBackingOff, EventReasonAdapterError, metav1.ConditionFalse, true),
		)

		It("backs off with AdapterError when the adapter is unreachable", func() {
			createSecret(validCredentials())
			createCR(resourceName, specFor("http://127.0.0.1:1"))

			p, _, err := reconcileAndFetch()
			Expect(err).To(HaveOccurred())
			expectState(p, dbaasv1.PhaseBackingOff, metav1.ConditionFalse, EventReasonAdapterError, metav1.ConditionFalse)
			Expect(aggregator.capturedPath).To(BeEmpty())
		})

		It("keeps the adapter answer of the last successful probe in the status", func() {
			createSecret(validCredentials())
			createCR(resourceName, baseSpec())
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())

			adapter.statusCode = http.StatusInternalServerError
			adapter.body = `{}`
			p, _, err := reconcileAndFetch()
			Expect(err).To(HaveOccurred())
			expectState(p, dbaasv1.PhaseBackingOff, metav1.ConditionFalse, EventReasonAdapterError, metav1.ConditionFalse)
			Expect(p.Status.PhysicalDatabaseID).To(Equal(physicalDatabaseID))
			Expect(p.Status.SupportedRoles).To(Equal([]string{"admin", "rw", "ro"}))
		})
	})

	// ── Aggregator errors ───────────────────────────────────────────────────────

	Context("when the aggregator rejects or fails the registration", func() {
		DescribeTable("maps the response with the shared aggregator classification",
			func(status int, phase dbaasv1.Phase, reason string, stalled metav1.ConditionStatus, wantErr bool) {
				createSecret(validCredentials())
				createCR(resourceName, baseSpec())
				aggregator.statusCode = status
				aggregator.body = `{"code":"CORE-DBAAS-4011","message":"Invalid physical identifier"}`

				p, _, err := reconcileAndFetch()
				Expect(err != nil).To(Equal(wantErr))
				expectState(p, phase, metav1.ConditionFalse, reason, stalled)
				Expect(p.Status.PhysicalDatabaseID).To(Equal(physicalDatabaseID), "the adapter answer is kept")
			},
			Entry("401", http.StatusUnauthorized, dbaasv1.PhaseBackingOff, EventReasonUnauthorized, metav1.ConditionFalse, true),
			Entry("409", http.StatusConflict, dbaasv1.PhaseInvalidConfiguration, EventReasonAggregatorRejected, metav1.ConditionTrue, false),
			Entry("500", http.StatusInternalServerError, dbaasv1.PhaseBackingOff, EventReasonAggregatorError, metav1.ConditionFalse, true),
		)
	})

	// ── Secret ──────────────────────────────────────────────────────────────────

	Context("when the adapter credentials Secret cannot be used", func() {
		DescribeTable("backs off with SecretError without probing the adapter",
			func(data map[string][]byte) {
				if data != nil {
					createSecret(data)
				}
				createCR(resourceName, baseSpec())

				p, _, err := reconcileAndFetch()
				Expect(err).To(HaveOccurred())
				expectState(p, dbaasv1.PhaseBackingOff, metav1.ConditionFalse, EventReasonSecretError, metav1.ConditionFalse)
				Expect(adapter.calls).To(BeZero())
				Expect(aggregator.capturedPath).To(BeEmpty())
			},
			Entry("the Secret does not exist", nil),
			Entry("the password key is missing", map[string][]byte{"username": []byte("dbaas-aggregator")}),
			Entry("the password key is empty", map[string][]byte{"username": []byte("dbaas-aggregator"), "password": {}}),
			Entry("the keys have other names", map[string][]byte{"user": []byte("dbaas-aggregator"), "pass": []byte("x")}),
		)
	})

	// ── Operator assignment ─────────────────────────────────────────────────────

	Context("when the resource is assigned to another operator", func() {
		It("leaves the status untouched and calls nothing", func() {
			createSecret(validCredentials())
			spec := baseSpec()
			spec.OperatorNamespace = testForeignOperatorNamespace
			createCR(resourceName, spec)

			p, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.IsZero()).To(BeTrue())
			Expect(p.Status.Phase).To(BeEmpty())
			Expect(adapter.calls).To(BeZero())
			Expect(aggregator.capturedPath).To(BeEmpty())
		})
	})

	// ── Sibling conflicts ───────────────────────────────────────────────────────

	Context("when two resources claim the same adapterAddress", func() {
		It("stalls the younger one before any remote call and lets it recover once the older one is deleted", func() {
			createSecret(validCredentials())
			createCR(resourceName, baseSpec())
			createCR(siblingName, baseSpec())
			olderKey, youngerKey := olderAndYounger()
			expectIndexed(physicalDatabaseAddressIndex, adapter.server.URL, 2)

			By("stalling the younger resource before the Secret read and the probe")
			p, result, err := reconcileAndFetchNamed(youngerKey)
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			expectState(p, dbaasv1.PhaseInvalidConfiguration, metav1.ConditionFalse, EventReasonRegistrationConflict, metav1.ConditionTrue)
			Expect(adapter.calls).To(BeZero())
			expectRecordedEventContaining(aggregator.recorder.Events, corev1.EventTypeWarning,
				EventReasonRegistrationConflict, olderKey.Name)

			By("registering the older resource")
			p, _, err = reconcileAndFetchNamed(olderKey)
			Expect(err).NotTo(HaveOccurred())
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)

			By("re-enqueueing the younger resource when the older one changes")
			Expect(reconciler.enqueueSiblings(ctx, p)).To(ConsistOf(reconcile.Request{NamespacedName: youngerKey}))

			By("recovering once the older resource is deleted")
			Expect(k8sClient.Delete(ctx, p)).To(Succeed())
			Eventually(func() bool {
				return apierrors.IsNotFound(cacheClient.Get(ctx, olderKey, &dbaasv1.PhysicalDatabase{}))
			}).Should(BeTrue())
			p, _, err = reconcileAndFetchNamed(youngerKey)
			Expect(err).NotTo(HaveOccurred())
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)
		})

		It("ignores an older resource assigned to another operator", func() {
			createSecret(validCredentials())
			foreign := baseSpec()
			foreign.OperatorNamespace = testForeignOperatorNamespace
			createCR(siblingName, foreign)
			createCR(resourceName, baseSpec())
			expectIndexed(physicalDatabaseAddressIndex, adapter.server.URL, 2)

			p, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)
		})
	})

	Context("when two adapters at different addresses report the same physicalDatabaseId", func() {
		It("stalls the younger one after its probe, without registering it", func() {
			otherAdapter := newAdapterProbeFixture(physicalDatabaseID)
			DeferCleanup(otherAdapter.server.Close)
			createSecret(validCredentials())
			createCR(resourceName, baseSpec())
			createCR(siblingName, specFor(otherAdapter.server.URL))
			olderKey, youngerKey := olderAndYounger()

			By("registering the older resource, which records the identifier in its status")
			p, _, err := reconcileAndFetchNamed(olderKey)
			Expect(err).NotTo(HaveOccurred())
			expectState(p, dbaasv1.PhaseSucceeded, metav1.ConditionTrue, EventReasonPhysicalDatabaseRegistered, metav1.ConditionFalse)
			expectIndexed(physicalDatabaseIDIndex, physicalDatabaseID, 1)
			expectRecordedEvent(aggregator.recorder.Events, corev1.EventTypeNormal, EventReasonPhysicalDatabaseRegistered)
			aggregator.reset()

			By("stalling the younger resource once its probe reports the same identifier")
			p, result, err := reconcileAndFetchNamed(youngerKey)
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			expectState(p, dbaasv1.PhaseInvalidConfiguration, metav1.ConditionFalse, EventReasonRegistrationConflict, metav1.ConditionTrue)
			Expect(p.Status.PhysicalDatabaseID).To(Equal(physicalDatabaseID))
			Expect(adapter.calls+otherAdapter.calls).To(Equal(2), "both adapters were probed")
			Expect(aggregator.capturedPath).To(BeEmpty(), "the younger resource must not be registered")
			expectRecordedEventContaining(aggregator.recorder.Events, corev1.EventTypeWarning,
				EventReasonRegistrationConflict, "physicalDatabaseId")

			By("re-enqueueing the younger resource through the identifier index")
			expectIndexed(physicalDatabaseIDIndex, physicalDatabaseID, 2)
			older := &dbaasv1.PhysicalDatabase{}
			Expect(cacheClient.Get(ctx, olderKey, older)).To(Succeed())
			Expect(reconciler.enqueueSiblings(ctx, older)).To(ConsistOf(reconcile.Request{NamespacedName: youngerKey}))
		})
	})

	// ── Admission ───────────────────────────────────────────────────────────────

	Context("when the spec violates the CRD schema", func() {
		DescribeTable("rejects the resource at admission",
			func(mutate func(*dbaasv1.PhysicalDatabaseSpec), wantMessage string) {
				spec := baseSpec()
				mutate(&spec)
				err := k8sClient.Create(ctx, &dbaasv1.PhysicalDatabase{
					ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
					Spec:       spec,
				})
				Expect(apierrors.IsInvalid(err)).To(BeTrue(), "error: %v", err)
				Expect(err.Error()).To(ContainSubstring(wantMessage))
			},
			Entry("an address without a scheme",
				func(s *dbaasv1.PhysicalDatabaseSpec) { s.AdapterAddress = "pg-dbaas-adapter:8080" },
				"spec.adapterAddress"),
			Entry("an address without a host",
				func(s *dbaasv1.PhysicalDatabaseSpec) { s.AdapterAddress = "http://" },
				"spec.adapterAddress"),
			Entry("no Secret name",
				func(s *dbaasv1.PhysicalDatabaseSpec) { s.CredentialsSecretRef.Name = "" },
				"spec.credentialsSecretRef.name"),
			Entry("an operatorNamespace that is not a namespace name",
				func(s *dbaasv1.PhysicalDatabaseSpec) { s.OperatorNamespace = "Dbaas_System" },
				"spec.operatorNamespace"),
		)

		It("rejects a change of operatorNamespace", func() {
			cr := createCR(resourceName, baseSpec())
			cr.Spec.OperatorNamespace = "other-namespace"
			err := k8sClient.Update(ctx, cr)
			Expect(apierrors.IsInvalid(err)).To(BeTrue(), "error: %v", err)
			Expect(err.Error()).To(ContainSubstring("spec.operatorNamespace is immutable after creation"))
		})

		It("accepts a change of adapterAddress", func() {
			cr := createCR(resourceName, baseSpec())
			cr.Spec.AdapterAddress = "https://pg-dbaas-adapter.postgres:8443"
			Expect(k8sClient.Update(ctx, cr)).To(Succeed())
		})
	})
})

var _ = Describe("siblingClaimChangedPredicate", func() {
	pred := siblingClaimChangedPredicate{}

	pdWith := func(generation int64, physicalDatabaseID, lastRequestID string) *dbaasv1.PhysicalDatabase {
		pd := &dbaasv1.PhysicalDatabase{ObjectMeta: metav1.ObjectMeta{Name: "pd", Namespace: "default", Generation: generation}}
		pd.Status.PhysicalDatabaseID = physicalDatabaseID
		pd.Status.LastRequestID = lastRequestID
		return pd
	}

	It("fires when the generation changed (spec update)", func() {
		Expect(pred.Update(event.UpdateEvent{ObjectOld: pdWith(1, "pg", "r1"), ObjectNew: pdWith(2, "pg", "r1")})).To(BeTrue())
	})

	It("fires when status.physicalDatabaseId changed", func() {
		Expect(pred.Update(event.UpdateEvent{ObjectOld: pdWith(1, "", "r1"), ObjectNew: pdWith(1, "pg", "r2")})).To(BeTrue())
	})

	It("does NOT fire on any other status write", func() {
		Expect(pred.Update(event.UpdateEvent{ObjectOld: pdWith(1, "pg", "r1"), ObjectNew: pdWith(1, "pg", "r2")})).To(BeFalse())
	})

	It("does NOT fire when objects are nil", func() {
		Expect(pred.Update(event.UpdateEvent{})).To(BeFalse())
	})

	It("fires on Create and Delete", func() {
		Expect(pred.Create(event.CreateEvent{Object: pdWith(1, "", "")})).To(BeTrue())
		Expect(pred.Delete(event.DeleteEvent{Object: pdWith(1, "", "")})).To(BeTrue())
	})
})
