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
	"fmt"
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

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

var _ = Describe("DatabaseSecret Controller", func() {
	const (
		ns           = "default"
		resourceName = "test-databasesecret"
		secretName   = "test-db-secret"
	)

	var (
		fixture        *aggregatorSyncFixture
		reconciler     *DatabaseSecretReconciler
		namespacedName types.NamespacedName
	)

	baseSpec := func() dbaasv1.DatabaseSecretSpec {
		return dbaasv1.DatabaseSecretSpec{
			Classifier: dbaasv1.Classifier{
				MicroserviceName: "test-service",
				Scope:            "service",
			},
			Type:       "postgresql",
			UserRole:   "admin",
			SecretName: secretName,
		}
	}

	successBody := func() string {
		b, _ := json.Marshal(map[string]any{
			"connectionProperties": map[string]any{
				"host":     "pg-patroni.dbaas-dev",
				"port":     5432,
				"username": "dbaas_abc123",
				"password": "secret",
				"url":      "jdbc:postgresql://pg-patroni.dbaas-dev:5432/mydb",
				"role":     "admin",
			},
		})
		return string(b)
	}

	BeforeEach(func() {
		fixture = newAggregatorSyncFixture()
		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}
		reconciler = &DatabaseSecretReconciler{
			Client:     cacheClient,
			Scheme:     cacheClient.Scheme(),
			Aggregator: aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(_ context.Context) (string, error) { return testToken, nil }),
			Recorder:   fixture.recorder,
			Ownership:  mineOwnershipResolver(ns),
		}
	})

	AfterEach(func() {
		fixture.close()
		deleteIfExists(&dbaasv1.DatabaseSecret{ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns}})
		deleteIfExists(&corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns}})
		// clean up any second CR created in duplicate-name tests
		deleteIfExists(&dbaasv1.DatabaseSecret{ObjectMeta: metav1.ObjectMeta{Name: resourceName + "-2", Namespace: ns}})
	})

	reconcileAndFetch := func() (*dbaasv1.DatabaseSecret, reconcile.Result, error) {
		return reconcileAndFetchObject(reconciler, namespacedName, func() *dbaasv1.DatabaseSecret {
			return &dbaasv1.DatabaseSecret{}
		})
	}

	// ── Pre-flight: label validation ─────────────────────────────────────────

	Context("Missing app.kubernetes.io/name label", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, InvalidSpec event, no aggregator call", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					// no app.kubernetes.io/name label
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			Expect(fixture.capturedPath).To(BeEmpty(), "aggregator must not be called")

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec,
				"app.kubernetes.io/name")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Happy path ────────────────────────────────────────────────────────────

	Context("HTTP 200 — Secret does not exist", func() {
		It("creates Secret, sets Phase=Succeeded, emits Normal/SecretCreated, ownerRef set", func() {
			fixture.statusCode = http.StatusOK
			fixture.body = successBody()
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(ds.Status.ObservedGeneration).To(Equal(ds.Generation))

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonSecretCreated))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			secret := &corev1.Secret{}
			Expect(k8sClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, secret)).To(Succeed())
			Expect(secret.Data).To(HaveKey("connectionProperties.json"))
			var cp map[string]any
			Expect(json.Unmarshal(secret.Data["connectionProperties.json"], &cp)).To(Succeed())
			Expect(cp).To(HaveKey("host"))
			Expect(cp).To(HaveKey("password"))
			Expect(cp["port"]).To(BeEquivalentTo(5432))
			Expect(secret.OwnerReferences).To(HaveLen(1))
			Expect(secret.OwnerReferences[0].Name).To(Equal(resourceName))
			Expect(secret.Labels).To(HaveKeyWithValue("app.kubernetes.io/managed-by", "dbaas-operator"))
			Expect(secret.Labels).To(HaveKeyWithValue("app.kubernetes.io/name", "test-service"))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonSecretCreated)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 200 — Secret already owned by this CR (update)", func() {
		It("updates Secret content, Phase=Succeeded", func() {
			fixture.statusCode = http.StatusOK
			fixture.body = successBody()

			ds := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, ds)).To(Succeed())

			// First reconcile — creates the Secret
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fixture.recorder.Events)

			// Second reconcile — updates
			got, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(got.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))

			secret := &corev1.Secret{}
			Expect(k8sClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, secret)).To(Succeed())
			Expect(secret.OwnerReferences).To(HaveLen(1))
			Expect(secret.Labels).To(HaveKeyWithValue("app.kubernetes.io/managed-by", "dbaas-operator"))
			Expect(secret.Labels).To(HaveKeyWithValue("app.kubernetes.io/name", "test-service"))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonSecretCreated)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 200 — empty connectionProperties", func() {
		It("sets Phase=BackingOff, Stalled=False, EmptyConnectionProperties event, requeues", func() {
			fixture.statusCode = http.StatusOK
			fixture.body = `{"connectionProperties":{}}`
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter))
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonEmptyConnectionProperties)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Aggregator error cases ────────────────────────────────────────────────

	Context("HTTP 404 — database not yet provisioned", func() {
		It("sets Phase=BackingOff, Stalled=False, DatabaseNotReady event, requeues", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = `{"code":"CORE-DBAAS-4006","message":"Database not found"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter))
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(ds.Status.ObservedGeneration).To(BeZero())

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonDatabaseNotFound))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonDatabaseNotFound)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 400 — invalid classifier", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, AggregatorRejected event", func() {
			fixture.statusCode = http.StatusBadRequest
			fixture.body = `{"code":"CORE-DBAAS-4010","message":"Invalid classifier","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected, "Invalid classifier")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 401 — operator credentials misconfigured", func() {
		It("sets Phase=BackingOff, Stalled=False, Unauthorized event, requeues", func() {
			fixture.statusCode = http.StatusUnauthorized
			fixture.body = body401Unauthorized
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonUnauthorized))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonUnauthorized)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 403 — role not permitted by DbPolicy", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, AggregatorRejected event, no requeue", func() {
			fixture.statusCode = http.StatusForbidden
			fixture.body = `{"code":"CORE-DBAAS-4023","message":"Role not permitted"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonAggregatorRejected))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected, "Role not permitted")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 500 — aggregator server error", func() {
		It("sets Phase=BackingOff, Stalled=False, AggregatorError event, requeues", func() {
			fixture.statusCode = http.StatusInternalServerError
			fixture.body = `{"code":"CORE-DBAAS-2000","message":"Unexpected exception","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError, "Unexpected exception")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 404 — BG edge: no active namespace (no TMF body)", func() {
		It("sets Phase=BackingOff, reason=AggregatorError, requeues", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = ``
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(ds.Status.ObservedGeneration).To(BeZero())

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 500 — BG edge: CORE-DBAAS-4041 no DB in active namespace", func() {
		It("sets Phase=BackingOff, reason=AggregatorError, requeues", func() {
			fixture.statusCode = http.StatusInternalServerError
			fixture.body = `{"code":"CORE-DBAAS-4041","message":"Can't find database in active namespace"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(ds.Status.ObservedGeneration).To(BeZero())

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			ready := findCondition(ds.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError, "Can't find database in active namespace")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Network error — aggregator unreachable", func() {
		It("sets Phase=BackingOff, Stalled=False, AggregatorError event, requeues", func() {
			fixture.server.Close()
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Pre-flight: Secret conflict ───────────────────────────────────────────

	Context("Pre-flight: Secret exists with foreign ownerRef", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, SecretConflict event, no aggregator call", func() {
			// Create a Secret owned by something else
			other := &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      secretName,
					Namespace: ns,
					OwnerReferences: []metav1.OwnerReference{
						{APIVersion: "v1", Kind: "Pod", Name: "other", UID: "other-uid", Controller: new(true)},
					},
				},
			}
			Expect(k8sClient.Create(ctx, other)).To(Succeed())

			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			Expect(fixture.capturedPath).To(BeEmpty(), "aggregator must not be called")

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretConflict)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Pre-flight: Secret exists with no ownerRef", func() {
		It("sets Phase=InvalidConfiguration, Stalled=True, SecretConflict event, no aggregator call", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
			})).To(Succeed())

			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			ds, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(ds.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			Expect(fixture.capturedPath).To(BeEmpty(), "aggregator must not be called")

			stalled := findCondition(ds.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretConflict)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Pre-flight: two DatabaseSecret CRs share the same secretName", func() {
		It("second CR gets Phase=InvalidConfiguration, Stalled=True, SecretConflict event", func() {
			fixture.statusCode = http.StatusOK
			fixture.body = successBody()

			cr1 := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, cr1)).To(Succeed())
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fixture.recorder.Events)

			// Second CR with same secretName
			cr2Name := resourceName + "-2"
			spec2 := baseSpec()
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      cr2Name,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: spec2,
			})).To(Succeed())

			reconciler2 := &DatabaseSecretReconciler{
				Client:     cacheClient,
				Scheme:     cacheClient.Scheme(),
				Aggregator: aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(_ context.Context) (string, error) { return testToken, nil }),
				Recorder:   fixture.recorder,
				Ownership:  mineOwnershipResolver(ns),
			}
			cr2Key := types.NamespacedName{Name: cr2Name, Namespace: ns}
			Eventually(func() error {
				return cacheClient.Get(ctx, cr2Key, &dbaasv1.DatabaseSecret{})
			}).Should(Succeed())
			result2, err2 := reconciler2.Reconcile(ctx, reconcile.Request{NamespacedName: cr2Key})
			Expect(err2).NotTo(HaveOccurred())
			Expect(result2.RequeueAfter).To(BeZero())

			ds2 := &dbaasv1.DatabaseSecret{}
			Expect(k8sClient.Get(ctx, types.NamespacedName{Name: cr2Name, Namespace: ns}, ds2)).To(Succeed())
			Expect(ds2.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))

			stalled := findCondition(ds2.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretConflict)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Ownership / namespace ─────────────────────────────────────────────────

	Context("Foreign namespace — ownership check skips reconcile", func() {
		It("does not update status and does not call aggregator", func() {
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			foreignReconciler := &DatabaseSecretReconciler{
				Client:     k8sClient,
				Scheme:     k8sClient.Scheme(),
				Aggregator: aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(_ context.Context) (string, error) { return testToken, nil }),
				Recorder:   fixture.recorder,
				Ownership:  foreignOwnershipResolver(ns),
			}
			_, err := foreignReconciler.Reconcile(ctx, reconcile.Request{NamespacedName: namespacedName})
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedPath).To(BeEmpty(), "aggregator must not be called for foreign namespace")

			ds := &dbaasv1.DatabaseSecret{}
			Expect(k8sClient.Get(ctx, namespacedName, ds)).To(Succeed())
			Expect(ds.Status.Phase).To(BeEmpty())
		})
	})

	// ── Path assertion ────────────────────────────────────────────────────────

	Context("Aggregator path assertion", func() {
		It("POSTs to /api/v3/dbaas/{ns}/databases/get-by-classifier/{type}", func() {
			fixture.statusCode = http.StatusOK
			fixture.body = successBody()
			Expect(k8sClient.Create(ctx, &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedPath).To(Equal(
				fmt.Sprintf("/api/v3/dbaas/%s/databases/get-by-classifier/%s", ns, "postgresql"),
			))
		})
	})
})

// ── Rate limiter / SetupWithManager ──────────────────────────────────────────

var _ = Describe("DatabaseSecret Controller — rate limiter", func() {
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

		err = (&DatabaseSecretReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Recorder:   mgr.GetEventRecorderFor("ds-rate-limiter-test"), //nolint:staticcheck
			Aggregator: aggregatorclient.NewClientWithTokenFunc("http://localhost:9999", func(_ context.Context) (string, error) { return testToken, nil }),
			Ownership:  mineOwnershipResolver("ns"),
		}).SetupWithManager(mgr, ctrlcontroller.Options{RateLimiter: rateLimiter})
		Expect(err).NotTo(HaveOccurred())

		req := reconcile.Request{NamespacedName: types.NamespacedName{Name: "ds", Namespace: "ns"}}
		Expect(rateLimiter.When(req)).To(Equal(base))
		Expect(rateLimiter.When(req)).To(Equal(2 * base))

		rateLimiter.Forget(req)
		Expect(rateLimiter.When(req)).To(Equal(base))
	})
})

//go:fix inline
func ptr[T any](v T) *T { return new(v) }
