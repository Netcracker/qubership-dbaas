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
	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/event"
	httpserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

var _ = Describe("DatabaseSecret Controller", func() {
	const (
		ns           = "default"
		resourceName = "test-databasesecret"
		secretName   = "test-db-secret"

		// databaseNotFoundResponseBody is the canonical TMF error body the aggregator
		// returns when a get-by-classifier request finds no matching database.
		databaseNotFoundResponseBody = `{"code":"CORE-DBAAS-4006","message":"Database not found"}`
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
			Expect(result.RequeueAfter).To(Equal(secretRotationSafetyNetInterval),
				"a successful reconcile schedules the safety-net re-poll")
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

	Context("HTTP 200 — Secret already owned by this CR, content unchanged", func() {
		It("is a no-op: Phase=Succeeded, no event, LastRotatedAt stays nil", func() {
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

			// First reconcile — creates the Secret (SecretCreated).
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fixture.recorder.Events)

			// Second reconcile with identical aggregator response — must be a
			// no-op: no Secret write, no event, no LastRotatedAt stamp.
			got, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(got.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(got.Status.LastRotatedAt).To(BeNil(),
				"no-op reconcile must not advance LastRotatedAt")

			ready := findCondition(got.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonSecretCreated),
				"steady-state Ready reason stays SecretCreated, not SecretRotated")

			secret := &corev1.Secret{}
			Expect(k8sClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, secret)).To(Succeed())
			Expect(secret.OwnerReferences).To(HaveLen(1))
			Expect(secret.Labels).To(HaveKeyWithValue("app.kubernetes.io/managed-by", "dbaas-operator"))
			Expect(secret.Labels).To(HaveKeyWithValue("app.kubernetes.io/name", "test-service"))

			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 200 — Secret already owned by this CR, content changed (rotation)", func() {
		It("rewrites the Secret, emits SecretRotated, stamps LastRotatedAt", func() {
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

			// First reconcile — creates the Secret with the original password.
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fixture.recorder.Events)

			// Aggregator now returns a rotated password.
			fixture.body = `{"connectionProperties":{"host":"pg-patroni.dbaas-dev","port":5432,"username":"dbaas_abc123","password":"ROTATED","url":"jdbc:postgresql://pg-patroni.dbaas-dev:5432/mydb","role":"admin"}}`

			got, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(got.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(got.Status.LastRotatedAt).NotTo(BeNil(),
				"a real content change must stamp LastRotatedAt")

			ready := findCondition(got.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonSecretRotated))

			// Secret data reflects the rotated password.
			secret := &corev1.Secret{}
			Expect(k8sClient.Get(ctx, types.NamespacedName{Name: secretName, Namespace: ns}, secret)).To(Succeed())
			var cp map[string]any
			Expect(json.Unmarshal(secret.Data["connectionProperties.json"], &cp)).To(Succeed())
			Expect(cp).To(HaveKeyWithValue("password", "ROTATED"))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonSecretRotated)
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
			fixture.body = databaseNotFoundResponseBody
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
			Expect(ds.Status.FirstNotFoundAt).NotTo(BeNil(), "first 404 must stamp FirstNotFoundAt")

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

	Context("HTTP 404 — DatabaseNotFound streak crosses the timeout", func() {
		It("switches Ready.Reason to DatabaseNotFoundTimeout and emits the one-shot Warning", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = databaseNotFoundResponseBody

			ds := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, ds)).To(Succeed())

			// Backdate FirstNotFoundAt past the timeout to simulate a long-stuck
			// CR without sleeping the test for 10 minutes.
			Eventually(func() error {
				return k8sClient.Get(ctx, namespacedName, ds)
			}).Should(Succeed())
			past := metav1.NewTime(time.Now().Add(-databaseNotFoundTimeout - time.Minute))
			ds.Status.FirstNotFoundAt = &past
			Expect(k8sClient.Status().Update(ctx, ds)).To(Succeed())
			// Wait for the cache (used by Reconcile via cacheClient) to observe
			// the backdated FirstNotFoundAt; otherwise Reconcile may see a stale
			// nil value and skip the timeout branch.
			Eventually(func() *metav1.Time {
				cur := &dbaasv1.DatabaseSecret{}
				if err := cacheClient.Get(ctx, namespacedName, cur); err != nil {
					return nil
				}
				return cur.Status.FirstNotFoundAt
			}).ShouldNot(BeNil())

			got, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(Equal(pollRequeueAfter),
				"polling must continue so the CR can self-heal if the database appears later")
			Expect(got.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff),
				"phase must remain BackingOff — timeout is informational, not permanent")
			Expect(got.Status.FirstNotFoundAt).NotTo(BeNil(),
				"FirstNotFoundAt must be preserved across the threshold crossing")

			ready := findCondition(got.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonDatabaseNotFoundTimeout),
				"Ready.Reason must flip to DatabaseNotFoundTimeout after the threshold")

			stalled := findCondition(got.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse),
				"Stalled must stay False — convention is reserved for permanent failures")

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonDatabaseNotFoundTimeout)
			expectNoRecordedEvent(fixture.recorder.Events)
		})

		It("does not re-emit the timeout event on subsequent reconciles", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = databaseNotFoundResponseBody

			ds := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, ds)).To(Succeed())

			Eventually(func() error {
				return k8sClient.Get(ctx, namespacedName, ds)
			}).Should(Succeed())
			past := metav1.NewTime(time.Now().Add(-databaseNotFoundTimeout - time.Minute))
			ds.Status.FirstNotFoundAt = &past
			Expect(k8sClient.Status().Update(ctx, ds)).To(Succeed())
			Eventually(func() *metav1.Time {
				cur := &dbaasv1.DatabaseSecret{}
				if err := cacheClient.Get(ctx, namespacedName, cur); err != nil {
					return nil
				}
				return cur.Status.FirstNotFoundAt
			}).ShouldNot(BeNil())

			// First reconcile crosses the threshold and emits the timeout event.
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonDatabaseNotFoundTimeout)
			drainRecordedEvents(fixture.recorder.Events)

			// Second reconcile must NOT re-emit (Ready.Reason already DatabaseNotFoundTimeout).
			got, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			ready := findCondition(got.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonDatabaseNotFoundTimeout))
			expectNoRecordedEvent(fixture.recorder.Events)
		})

		It("clears FirstNotFoundAt and recovers when aggregator finally returns 200", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = databaseNotFoundResponseBody

			ds := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, ds)).To(Succeed())

			Eventually(func() error {
				return k8sClient.Get(ctx, namespacedName, ds)
			}).Should(Succeed())
			past := metav1.NewTime(time.Now().Add(-databaseNotFoundTimeout - time.Minute))
			ds.Status.FirstNotFoundAt = &past
			Expect(k8sClient.Status().Update(ctx, ds)).To(Succeed())
			Eventually(func() *metav1.Time {
				cur := &dbaasv1.DatabaseSecret{}
				if err := cacheClient.Get(ctx, namespacedName, cur); err != nil {
					return nil
				}
				return cur.Status.FirstNotFoundAt
			}).ShouldNot(BeNil())

			// One reconcile to enter the timeout state.
			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			drainRecordedEvents(fixture.recorder.Events)

			// Aggregator now returns the database — CR must recover.
			fixture.statusCode = http.StatusOK
			fixture.body = successBody()
			got, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(got.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(got.Status.FirstNotFoundAt).To(BeNil(),
				"FirstNotFoundAt must be cleared after a successful aggregator response")
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

	Context("Pre-flight: two CRs claim the same secretName before either has reconciled", func() {
		It("older CR wins, younger CR gets SecretConflict (no symmetric failure)", func() {
			fixture.statusCode = http.StatusOK
			fixture.body = successBody()

			// Create the older CR first (cr1), then the younger CR (cr2). Neither
			// has reconciled yet, so the target Secret does not exist. The conflict
			// is detected by the sibling-list check (not the ownership check).
			cr1 := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, cr1)).To(Succeed())

			// creationTimestamp has 1-second resolution. Sleep so cr2 ends up with
			// a strictly later timestamp, otherwise the tiebreak falls back to the
			// random UID order and the assertion becomes non-deterministic.
			time.Sleep(1100 * time.Millisecond)

			cr2Name := resourceName + "-2"
			cr2 := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      cr2Name,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, cr2)).To(Succeed())

			// Wait for the cache to observe both CRs (the field index lives in the
			// cache, so the sibling-list query needs both to be visible).
			cr1Key := types.NamespacedName{Name: resourceName, Namespace: ns}
			cr2Key := types.NamespacedName{Name: cr2Name, Namespace: ns}
			Eventually(func() error {
				if err := cacheClient.Get(ctx, cr1Key, &dbaasv1.DatabaseSecret{}); err != nil {
					return err
				}
				return cacheClient.Get(ctx, cr2Key, &dbaasv1.DatabaseSecret{})
			}).Should(Succeed())

			// Reconcile cr2 (the younger). It must lose to cr1.
			result2, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: cr2Key})
			Expect(err).NotTo(HaveOccurred())
			Expect(result2.RequeueAfter).To(BeZero())

			got2 := &dbaasv1.DatabaseSecret{}
			Expect(k8sClient.Get(ctx, cr2Key, got2)).To(Succeed())
			Expect(got2.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning,
				EventReasonSecretConflict, "older claimant wins")

			// Reconcile cr1 (the older). It must remain unaffected and succeed.
			result1, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: cr1Key})
			Expect(err).NotTo(HaveOccurred())
			Expect(result1.RequeueAfter).To(Equal(secretRotationSafetyNetInterval),
				"the older claimant succeeds and schedules the safety-net re-poll")

			got1 := &dbaasv1.DatabaseSecret{}
			Expect(k8sClient.Get(ctx, cr1Key, got1)).To(Succeed())
			Expect(got1.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded),
				"older claimant must not be marked SecretConflict by the younger sibling")
		})
	})

	Context("Pre-flight: loser CR recovers after winner is deleted", func() {
		It("re-reconciling the loser after winner deletion lets it succeed", func() {
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

			// creationTimestamp has 1-second resolution. Sleep so cr2 ends up with
			// a strictly later timestamp, otherwise the tiebreak falls back to the
			// random UID order and the assertion becomes non-deterministic.
			time.Sleep(1100 * time.Millisecond)

			cr2Name := resourceName + "-2"
			cr2 := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      cr2Name,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, cr2)).To(Succeed())

			cr1Key := types.NamespacedName{Name: resourceName, Namespace: ns}
			cr2Key := types.NamespacedName{Name: cr2Name, Namespace: ns}
			Eventually(func() error {
				if err := cacheClient.Get(ctx, cr1Key, &dbaasv1.DatabaseSecret{}); err != nil {
					return err
				}
				return cacheClient.Get(ctx, cr2Key, &dbaasv1.DatabaseSecret{})
			}).Should(Succeed())

			// cr2 loses initially.
			_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: cr2Key})
			Expect(err).NotTo(HaveOccurred())
			got2 := &dbaasv1.DatabaseSecret{}
			Expect(k8sClient.Get(ctx, cr2Key, got2)).To(Succeed())
			Expect(got2.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			drainRecordedEvents(fixture.recorder.Events)

			// Winner (cr1) is removed.
			Expect(k8sClient.Delete(ctx, cr1)).To(Succeed())
			Eventually(func() bool {
				err := cacheClient.Get(ctx, cr1Key, &dbaasv1.DatabaseSecret{})
				return err != nil
			}).Should(BeTrue())

			// cr2 must succeed on the next reconcile — the Watches-driven re-enqueue
			// is what would trigger this in production; here we simulate it directly.
			_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: cr2Key})
			Expect(err).NotTo(HaveOccurred())

			got2 = &dbaasv1.DatabaseSecret{}
			Expect(k8sClient.Get(ctx, cr2Key, got2)).To(Succeed())
			Expect(got2.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded),
				"loser must recover once the older claimant is deleted")
		})
	})

	Context("Watches: enqueueSiblingsBySecretName fans out by secretName", func() {
		It("returns only siblings sharing spec.secretName, excluding the source object", func() {
			cr1 := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      resourceName,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, cr1)).To(Succeed())

			// creationTimestamp has 1-second resolution. Sleep so cr2 ends up with
			// a strictly later timestamp, otherwise the tiebreak falls back to the
			// random UID order and the assertion becomes non-deterministic.
			time.Sleep(1100 * time.Millisecond)

			cr2Name := resourceName + "-2"
			cr2 := &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name:      cr2Name,
					Namespace: ns,
					Labels:    map[string]string{"app.kubernetes.io/name": "test-service"},
				},
				Spec: baseSpec(),
			}
			Expect(k8sClient.Create(ctx, cr2)).To(Succeed())

			cr1Key := types.NamespacedName{Name: resourceName, Namespace: ns}
			cr2Key := types.NamespacedName{Name: cr2Name, Namespace: ns}
			Eventually(func() error {
				if err := cacheClient.Get(ctx, cr1Key, &dbaasv1.DatabaseSecret{}); err != nil {
					return err
				}
				return cacheClient.Get(ctx, cr2Key, &dbaasv1.DatabaseSecret{})
			}).Should(Succeed())

			got1 := &dbaasv1.DatabaseSecret{}
			Expect(cacheClient.Get(ctx, cr1Key, got1)).To(Succeed())

			reqs := reconciler.enqueueSiblingsBySecretName(ctx, got1)
			names := make([]string, 0, len(reqs))
			for _, r := range reqs {
				names = append(names, r.Name)
			}
			Expect(names).To(ConsistOf(cr2Name),
				"the source object itself must be excluded; only siblings sharing secretName are returned")
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

// ── dbaasv1.ClassifierIndexKey + dbaasv1.ClassifierTypeIndex ────────────────

var _ = Describe("DatabaseSecret Controller — classifier+type field index", func() {
	const ns = "default"

	Context("dbaasv1.ClassifierIndexKey() canonicalization", func() {
		It("produces the same key for the same classifier content", func() {
			c1 := dbaasv1.Classifier{
				MicroserviceName: "svc-a", Scope: "service", Namespace: "team-x", TenantId: "t1",
			}
			c2 := dbaasv1.Classifier{
				MicroserviceName: "svc-a", Scope: "service", Namespace: "team-x", TenantId: "t1",
			}
			Expect(dbaasv1.ClassifierIndexKey(c1, "postgresql")).To(Equal(dbaasv1.ClassifierIndexKey(c2, "postgresql")))
		})

		It("differs when type differs", func() {
			c := dbaasv1.Classifier{MicroserviceName: "svc-a", Scope: "service"}
			Expect(dbaasv1.ClassifierIndexKey(c, "postgresql")).NotTo(Equal(dbaasv1.ClassifierIndexKey(c, "mongodb")))
		})

		It("differs when classifier scalar fields differ", func() {
			c1 := dbaasv1.Classifier{MicroserviceName: "svc-a", Scope: "service"}
			c2 := dbaasv1.Classifier{MicroserviceName: "svc-b", Scope: "service"}
			Expect(dbaasv1.ClassifierIndexKey(c1, "postgresql")).NotTo(Equal(dbaasv1.ClassifierIndexKey(c2, "postgresql")))
		})

		It("omits empty optional fields from the canonical form", func() {
			minimal := dbaasv1.Classifier{MicroserviceName: "svc", Scope: "service"}
			key := dbaasv1.ClassifierIndexKey(minimal, "postgresql")
			// tenantId / namespace / customKeys are absent in the JSON when empty.
			Expect(key).NotTo(ContainSubstring("tenantId"))
			Expect(key).NotTo(ContainSubstring("namespace"))
			Expect(key).NotTo(ContainSubstring("customKeys"))
		})

		It("is stable across customKeys ordering (json.Marshal sorts map keys)", func() {
			c1 := dbaasv1.Classifier{
				MicroserviceName: "svc", Scope: "service",
				CustomKeys: map[string]apiextensionsv1.JSON{
					"b": {Raw: []byte(`"vb"`)},
					"a": {Raw: []byte(`"va"`)},
				},
			}
			c2 := dbaasv1.Classifier{
				MicroserviceName: "svc", Scope: "service",
				CustomKeys: map[string]apiextensionsv1.JSON{
					"a": {Raw: []byte(`"va"`)},
					"b": {Raw: []byte(`"vb"`)},
				},
			}
			Expect(dbaasv1.ClassifierIndexKey(c1, "postgresql")).To(Equal(dbaasv1.ClassifierIndexKey(c2, "postgresql")))
		})

		It("includes nested customKeys structures in the canonical form", func() {
			c := dbaasv1.Classifier{
				MicroserviceName: "svc", Scope: "service",
				CustomKeys: map[string]apiextensionsv1.JSON{
					"logicalDBName": {Raw: []byte(`"billing"`)},
					"count":         {Raw: []byte(`42`)},
				},
			}
			key := dbaasv1.ClassifierIndexKey(c, "postgresql")
			Expect(key).To(ContainSubstring("logicalDBName"))
			Expect(key).To(ContainSubstring("billing"))
			Expect(key).To(ContainSubstring(`"count":42`))
		})
	})

	Context("cache field index lookup", func() {
		var (
			crA *dbaasv1.DatabaseSecret
			crB *dbaasv1.DatabaseSecret
			crC *dbaasv1.DatabaseSecret
		)

		BeforeEach(func() {
			crA = &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name: "idx-cr-a", Namespace: ns,
					Labels: map[string]string{"app.kubernetes.io/name": "svc-a"},
				},
				Spec: dbaasv1.DatabaseSecretSpec{
					Classifier: dbaasv1.Classifier{MicroserviceName: "svc-a", Scope: "service"},
					Type:       "postgresql",
					SecretName: "idx-secret-a",
				},
			}
			crB = &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name: "idx-cr-b", Namespace: ns,
					Labels: map[string]string{"app.kubernetes.io/name": "svc-b"},
				},
				Spec: dbaasv1.DatabaseSecretSpec{
					// Same classifier as A, same type → should match A's key.
					Classifier: dbaasv1.Classifier{MicroserviceName: "svc-a", Scope: "service"},
					Type:       "postgresql",
					SecretName: "idx-secret-b",
				},
			}
			crC = &dbaasv1.DatabaseSecret{
				ObjectMeta: metav1.ObjectMeta{
					Name: "idx-cr-c", Namespace: ns,
					Labels: map[string]string{"app.kubernetes.io/name": "svc-c"},
				},
				Spec: dbaasv1.DatabaseSecretSpec{
					// Different classifier — must NOT match A's key.
					Classifier: dbaasv1.Classifier{MicroserviceName: "svc-c", Scope: "service"},
					Type:       "postgresql",
					SecretName: "idx-secret-c",
				},
			}
			Expect(k8sClient.Create(ctx, crA)).To(Succeed())
			Expect(k8sClient.Create(ctx, crB)).To(Succeed())
			Expect(k8sClient.Create(ctx, crC)).To(Succeed())
		})

		AfterEach(func() {
			deleteIfExists(crA)
			deleteIfExists(crB)
			deleteIfExists(crC)
		})

		It("returns all CRs sharing classifier+type, excludes non-matching ones", func() {
			indexKey := dbaasv1.ClassifierIndexKey(crA.Spec.Classifier, crA.Spec.Type)

			// Wait for the cache to observe all three CRs before querying via the index.
			Eventually(func() (int, error) {
				list := &dbaasv1.DatabaseSecretList{}
				if err := cacheClient.List(ctx, list, client.InNamespace(ns)); err != nil {
					return 0, err
				}
				return len(list.Items), nil
			}).Should(BeNumerically(">=", 3))

			list := &dbaasv1.DatabaseSecretList{}
			Expect(cacheClient.List(ctx, list,
				client.InNamespace(ns),
				client.MatchingFields{dbaasv1.ClassifierTypeIndex: indexKey})).To(Succeed())

			names := make([]string, 0, len(list.Items))
			for i := range list.Items {
				names = append(names, list.Items[i].Name)
			}
			Expect(names).To(ConsistOf(crA.Name, crB.Name),
				"index lookup must return A+B (same classifier+type) and exclude C")
		})

		It("returns nothing for a classifier with no matching CR", func() {
			missingKey := dbaasv1.ClassifierIndexKey(
				dbaasv1.Classifier{MicroserviceName: "svc-nonexistent", Scope: "service"},
				"postgresql")
			list := &dbaasv1.DatabaseSecretList{}
			Expect(cacheClient.List(ctx, list,
				client.InNamespace(ns),
				client.MatchingFields{dbaasv1.ClassifierTypeIndex: missingKey})).To(Succeed())
			Expect(list.Items).To(BeEmpty())
		})
	})
})

// ── secretUpToDate ───────────────────────────────────────────────────────────

var _ = Describe("DatabaseSecret Controller — secretUpToDate", func() {
	cr := &dbaasv1.DatabaseSecret{
		ObjectMeta: metav1.ObjectMeta{
			Labels: map[string]string{"app.kubernetes.io/name": "svc-a"},
		},
	}
	desired := map[string][]byte{"connectionProperties.json": []byte(`{"password":"p1"}`)}

	managedSecret := func(data map[string][]byte, nameLabel string) *corev1.Secret {
		return &corev1.Secret{
			ObjectMeta: metav1.ObjectMeta{
				Labels: map[string]string{
					"app.kubernetes.io/managed-by": "dbaas-operator",
					"app.kubernetes.io/name":       nameLabel,
				},
			},
			Data: data,
		}
	}

	It("returns true when data and managed labels match", func() {
		existing := managedSecret(map[string][]byte{"connectionProperties.json": []byte(`{"password":"p1"}`)}, "svc-a")
		Expect(secretUpToDate(cr, existing, desired)).To(BeTrue())
	})

	It("returns false when the data differs", func() {
		existing := managedSecret(map[string][]byte{"connectionProperties.json": []byte(`{"password":"p2"}`)}, "svc-a")
		Expect(secretUpToDate(cr, existing, desired)).To(BeFalse())
	})

	It("returns false when an extra data key is present", func() {
		existing := managedSecret(map[string][]byte{
			"connectionProperties.json": []byte(`{"password":"p1"}`),
			"extra":                     []byte("x"),
		}, "svc-a")
		Expect(secretUpToDate(cr, existing, desired)).To(BeFalse())
	})

	It("returns false when the managed-by label is missing", func() {
		existing := &corev1.Secret{
			ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app.kubernetes.io/name": "svc-a"}},
			Data:       map[string][]byte{"connectionProperties.json": []byte(`{"password":"p1"}`)},
		}
		Expect(secretUpToDate(cr, existing, desired)).To(BeFalse())
	})

	It("returns false when the name label drifted", func() {
		existing := managedSecret(map[string][]byte{"connectionProperties.json": []byte(`{"password":"p1"}`)}, "svc-other")
		Expect(secretUpToDate(cr, existing, desired)).To(BeFalse())
	})
})

// ── specOrRotationTriggerPredicate ───────────────────────────────────────────

var _ = Describe("DatabaseSecret Controller — rotation-trigger predicate", func() {
	pred := specOrRotationTriggerPredicate{}

	secretWith := func(generation int64, rotationTrigger string) *dbaasv1.DatabaseSecret {
		ds := &dbaasv1.DatabaseSecret{
			ObjectMeta: metav1.ObjectMeta{
				Name:       "ds",
				Namespace:  "default",
				Generation: generation,
			},
		}
		if rotationTrigger != "" {
			ds.Annotations = map[string]string{dbaasv1.AnnotationRotationTrigger: rotationTrigger}
		}
		return ds
	}

	Describe("Update", func() {
		It("fires when the generation changed (spec update)", func() {
			ok := pred.Update(event.UpdateEvent{
				ObjectOld: secretWith(1, ""),
				ObjectNew: secretWith(2, ""),
			})
			Expect(ok).To(BeTrue())
		})

		It("fires when the rotation-trigger annotation changed", func() {
			ok := pred.Update(event.UpdateEvent{
				ObjectOld: secretWith(1, "evt-1"),
				ObjectNew: secretWith(1, "evt-2"),
			})
			Expect(ok).To(BeTrue())
		})

		It("fires when the rotation-trigger annotation is first added", func() {
			ok := pred.Update(event.UpdateEvent{
				ObjectOld: secretWith(1, ""),
				ObjectNew: secretWith(1, "evt-1"),
			})
			Expect(ok).To(BeTrue())
		})

		It("does NOT fire on a status-only update (no generation or annotation change)", func() {
			ok := pred.Update(event.UpdateEvent{
				ObjectOld: secretWith(3, "evt-1"),
				ObjectNew: secretWith(3, "evt-1"),
			})
			Expect(ok).To(BeFalse())
		})

		It("does NOT fire when only an unrelated annotation changed", func() {
			old := secretWith(1, "evt-1")
			old.Annotations["unrelated"] = "a"
			updated := secretWith(1, "evt-1")
			updated.Annotations["unrelated"] = "b"
			ok := pred.Update(event.UpdateEvent{ObjectOld: old, ObjectNew: updated})
			Expect(ok).To(BeFalse())
		})

		It("does NOT fire when objects are nil", func() {
			Expect(pred.Update(event.UpdateEvent{})).To(BeFalse())
		})
	})

	Describe("Create and Delete fall through to defaults", func() {
		It("fires on Create", func() {
			Expect(pred.Create(event.CreateEvent{Object: secretWith(1, "")})).To(BeTrue())
		})

		It("fires on Delete", func() {
			Expect(pred.Delete(event.DeleteEvent{Object: secretWith(1, "")})).To(BeTrue())
		})
	})
})

// ── Rate limiter / SetupWithManager ──────────────────────────────────────────

var _ = Describe("DatabaseSecret Controller — rate limiter", func() {
	It("registers the controller with a custom exponential rate limiter", func() {
		mgr, err := ctrl.NewManager(cfg, ctrl.Options{
			Scheme:                 k8sClient.Scheme(),
			Metrics:                httpserver.Options{BindAddress: "0"},
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
