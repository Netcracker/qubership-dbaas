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
	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	config "sigs.k8s.io/controller-runtime/pkg/config"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	httpserver "sigs.k8s.io/controller-runtime/pkg/metrics/server"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

var _ = Describe("ExternalDatabase Controller", func() {
	const (
		ns           = "default"
		resourceName = "test-edb"
		secretName   = "test-edb-secret"
	)

	var (
		fixture        *aggregatorSyncFixture
		reconciler     *ExternalDatabaseReconciler
		namespacedName types.NamespacedName
	)

	// baseSpec builds a minimal valid spec without any credentialsSecretRef.
	// Suitable for tests that only care about the aggregator response.
	baseSpec := func() dbaasv1.ExternalDatabaseSpec {
		return dbaasv1.ExternalDatabaseSpec{
			Classifier: dbaasv1.Classifier{
				MicroserviceName: "test-service",
				Namespace:        ns,
				Scope:            "service",
			},
			Type:   "postgresql",
			DbName: "testdb",
			ConnectionProperties: []dbaasv1.ConnectionProperty{
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
		fixture = newAggregatorSyncFixture()
		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}
		reconciler = &ExternalDatabaseReconciler{
			Client:     k8sClient,
			Scheme:     k8sClient.Scheme(),
			Aggregator: aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(_ context.Context) (string, error) { return testToken, nil }),
			Recorder:   fixture.recorder,
			Ownership:  mineOwnershipResolver(ns),
		}
	})

	AfterEach(func() {
		fixture.close()
		deleteIfExists(&dbaasv1.ExternalDatabase{ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns}})
		deleteIfExists(&corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns}})
	})

	// reconcileAndFetch calls Reconcile and re-fetches the CR from the API server.
	// The CR must be created before calling this helper.
	reconcileAndFetch := func() (*dbaasv1.ExternalDatabase, reconcile.Result, error) {
		return reconcileAndFetchObject(reconciler, namespacedName, func() *dbaasv1.ExternalDatabase {
			return &dbaasv1.ExternalDatabase{}
		})
	}

	// ── Request assembly ──────────────────────────────────────────────────────

	Context("buildRequest — ExtraProperties vs typed fields priority", func() {
		It("typed fields take precedence over ExtraProperties with the same key", func() {
			spec := baseSpec()
			spec.ConnectionProperties = []dbaasv1.ConnectionProperty{
				{
					Role: "primary",
					// "role" in ExtraProperties must NOT win over the typed Role field.
					ExtraProperties: map[string]string{
						"role": "admin",
					},
				},
			}
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedBody).NotTo(BeEmpty())

			var sent struct {
				ConnectionProperties       []map[string]string `json:"connectionProperties"`
				UpdateConnectionProperties bool                `json:"updateConnectionProperties"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())
			Expect(sent.ConnectionProperties).To(HaveLen(1))

			props := sent.ConnectionProperties[0]
			Expect(props["role"]).To(Equal("primary"),
				"typed Role must override ExtraProperties role")
			Expect(sent.UpdateConnectionProperties).To(BeTrue(),
				"updateConnectionProperties must always be sent as true")
		})
	})

	Context("buildRequest — customKeys serialization", func() {
		It("sends customKeys as a nested object, not flattened to top level", func() {
			// Regression guard: the aggregator stores the classifier verbatim and
			// reads classifier.customKeys.* as a nested map (canonical dbaas-client
			// shape). Flattening customKeys to the top level would register the
			// database under a classifier no consumer can match.
			spec := baseSpec()
			spec.Classifier.CustomKeys = map[string]apiextensionsv1.JSON{
				"logicalDBName": {Raw: []byte(`"configs"`)},
				"shardCount":    {Raw: []byte(`5`)},
			}
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedBody).NotTo(BeEmpty())

			var sent struct {
				Classifier map[string]any `json:"classifier"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())

			// customKeys entries must NOT leak to the top level.
			Expect(sent.Classifier).NotTo(HaveKey("logicalDBName"))
			Expect(sent.Classifier).NotTo(HaveKey("shardCount"))

			// They live inside a nested "customKeys" object, preserving JSON types.
			ck, ok := sent.Classifier["customKeys"].(map[string]any)
			Expect(ok).To(BeTrue(), "customKeys must be a nested object inside the classifier")
			Expect(ck).To(HaveKeyWithValue("logicalDBName", "configs"))
			Expect(ck).To(HaveKeyWithValue("shardCount", float64(5)))
		})

		It("flattens extraKeys onto the classifier top level (legacy open-classifier shape)", func() {
			// extraKeys reproduce the legacy model where services placed arbitrary
			// identity fields directly at the classifier top level (dbaas-client
			// withProperty/withProperties). They sit alongside the scalars, NOT
			// nested — both sides must produce the same set for identity to match.
			spec := baseSpec()
			spec.Classifier.ExtraKeys = map[string]apiextensionsv1.JSON{
				"region": {Raw: []byte(`"eu"`)},
				"legacy": {Raw: []byte(`{"nested":true}`)},
			}
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedBody).NotTo(BeEmpty())

			var sent struct {
				Classifier map[string]any `json:"classifier"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())

			// extraKeys land on the top level, preserving JSON types, and never
			// appear as a nested "extraKeys" object.
			Expect(sent.Classifier).To(HaveKeyWithValue("region", "eu"))
			Expect(sent.Classifier).To(HaveKeyWithValue("legacy", map[string]any{"nested": true}))
			Expect(sent.Classifier).NotTo(HaveKey("extraKeys"))
		})
	})

	// ── classifier.namespace validation ──────────────────────────────────────

	Context("classifier.namespace absent — falls back to metadata.namespace", func() {
		It("uses CR namespace in the aggregator URL and request body, and succeeds", func() {
			spec := baseSpec()
			spec.Classifier.Namespace = ""
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedPath).To(Equal("/api/v3/dbaas/" + ns + "/databases/registration/externally_manageable"))
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))

			// The classifier in the request body must also carry the defaulted
			// namespace — the aggregator's isValidClassifierV3 rejects a classifier
			// without it, regardless of the namespace embedded in the URL path.
			Expect(fixture.capturedBody).NotTo(BeEmpty())
			var sent struct {
				Classifier map[string]any `json:"classifier"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())
			Expect(sent.Classifier).To(HaveKeyWithValue("namespace", ns))
		})
	})

	Context("classifier.namespace matches metadata.namespace", func() {
		It("proceeds normally and succeeds", func() {
			spec := baseSpec()
			spec.Classifier.Namespace = ns // same as metadata.namespace
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
		})
	})

	Context("classifier.namespace does not match metadata.namespace", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/InvalidSpec, Stalled=True, does not requeue, never calls aggregator", func() {
			spec := baseSpec()
			spec.Classifier.Namespace = "other-namespace"
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			// Aggregator must not be called.
			Expect(fixture.capturedBody).To(BeEmpty())

			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring(`"other-namespace"`))
			Expect(ready.Message).To(ContainSubstring(ns))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionTrue))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("classifier.extraKeys contains a reserved key", func() {
		It("sets Phase=InvalidConfiguration, never calls aggregator", func() {
			spec := baseSpec()
			spec.Classifier.ExtraKeys = map[string]apiextensionsv1.JSON{
				"scope":  {Raw: []byte(`"tenant"`)}, // reserved — owned by the typed field
				"region": {Raw: []byte(`"eu"`)},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.capturedBody).To(BeEmpty(), "aggregator must not be called")

			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring("extraKeys"))
			Expect(ready.Message).To(ContainSubstring("scope"))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
		})
	})

	// ── Success cases ─────────────────────────────────────────────────────────

	Context("HTTP 200 — database already registered (no update)", func() {
		It("sets Phase=Succeeded, Ready=True, Stalled=False, emits Normal/Registered event, does not requeue", func() {
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonDatabaseRegistered))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(ReasonSucceeded))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonDatabaseRegistered)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 201 — database successfully created or updated", func() {
		It("sets Phase=Succeeded, Ready=True, Stalled=False, emits Normal/Registered event, does not requeue", func() {
			fixture.statusCode = http.StatusCreated
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonDatabaseRegistered))
			Expect(ready.ObservedGeneration).To(Equal(edb.Generation))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))
			Expect(stalled.Reason).To(Equal(ReasonSucceeded))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonDatabaseRegistered)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Secret resolution error ───────────────────────────────────────────────

	Context("Secret referenced in credentialsSecretRef does not exist", func() {
		It("sets Phase=BackingOff, Ready=False/SecretError, Stalled=False, emits Warning/SecretError event, requeues", func() {
			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: "non-existent-secret",
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "username", Name: "username"},
					{Key: "password", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
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

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Secret exists but is missing a required key", func() {
		It("sets Phase=BackingOff, Ready=False/SecretError, Stalled=False, emits Warning/SecretError event, requeues", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					// Secret exists but does not contain "db-user".
					"irrelevant-key": []byte("irrelevant-value"),
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonSecretError))
			Expect(ready.Message).To(ContainSubstring(`missing key "db-user"`))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Secret exists but the mapped key value is empty", func() {
		It("sets Phase=BackingOff, SecretError (not Unauthorized), Stalled=False, requeues", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					"db-user": []byte(""), // key present but empty
					"db-pass": []byte("s3cr3t"),
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			// Must be SecretError, not Unauthorized — the aggregator is never called.
			Expect(ready.Reason).To(Equal(EventReasonSecretError))
			Expect(ready.Message).To(ContainSubstring(`key "db-user" is empty`))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Secret exists but the second mapped key value is empty", func() {
		It("sets Phase=BackingOff, SecretError, message mentions the empty key", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					"db-user": []byte("admin"),
					"db-pass": []byte(""), // key present but empty
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonSecretError))
			Expect(ready.Message).To(ContainSubstring(`key "db-pass" is empty`))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("duplicate name in credentialsSecretRef.keys (defence-in-depth)", func() {
		It("sets Phase=InvalidConfiguration, InvalidSpec, message mentions duplicate name", func() {
			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-user2", Name: "username"}, // duplicate name
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready.Reason).To(Equal(EventReasonInvalidSpec))
			Expect(ready.Message).To(ContainSubstring(`duplicate name "username"`))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonInvalidSpec)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("multiple keys mapped from Secret override matching extraProperties", func() {
		It("sends all mapped keys in the aggregator request and overrides extraProperties", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					"db-user": []byte("admin"),
					"db-pass": []byte("s3cr3t"),
					"ssl-ca":  []byte("ca-cert-data"),
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].ExtraProperties = map[string]string{
				"host":     "pg.example.com",
				"port":     "5432",
				"username": "should-be-overridden", // will be overridden by Secret
			}
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
					{Key: "ssl-ca", Name: "sslCA"},
				},
			}
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			_, _, err := reconcileAndFetch()
			Expect(err).NotTo(HaveOccurred())
			Expect(fixture.capturedBody).NotTo(BeEmpty())

			var sent struct {
				ConnectionProperties []map[string]string `json:"connectionProperties"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())
			Expect(sent.ConnectionProperties).To(HaveLen(1))

			props := sent.ConnectionProperties[0]
			Expect(props["username"]).To(Equal("admin"),
				"Secret value must override extraProperties")
			Expect(props["password"]).To(Equal("s3cr3t"))
			Expect(props["sslCA"]).To(Equal("ca-cert-data"))
			Expect(props["host"]).To(Equal("pg.example.com"))
			Expect(props["port"]).To(Equal("5432"))
		})
	})

	// ── credentialsSecretRef — key resolution ────────────────────────────────
	//
	// Four canonical scenarios covering the full key-resolution lifecycle:
	//   1. Secret does not exist.
	//   2. Secret exists but has no data (empty).
	//   3. Secret has data but the mapped key is absent.
	//   4. All keys present — credentials injected, aggregator call succeeds.

	Context("credentialsSecretRef — Secret does not exist", func() {
		It("returns error, sets Phase=BackingOff / SecretError, never calls aggregator", func() {
			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: "ghost-secret",
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			// Controller must return an error so controller-runtime applies back-off.
			Expect(err).To(HaveOccurred())
			// Aggregator must not be called.
			Expect(fixture.capturedBody).To(BeEmpty())

			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonSecretError))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("credentialsSecretRef — Secret exists but has no data (empty Secret)", func() {
		It("returns error, sets Phase=BackingOff / SecretError, never calls aggregator", func() {
			// Secret exists but Data map is completely empty.
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				// Data intentionally omitted — equivalent to an empty map.
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(fixture.capturedBody).To(BeEmpty())

			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonSecretError))
			// First mapping key "db-user" is checked first and reported as missing.
			Expect(ready.Message).To(ContainSubstring(`missing key "db-user"`))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("credentialsSecretRef — Secret has data but the mapped key is absent", func() {
		It("returns error, sets Phase=BackingOff / SecretError, message names the missing key, never calls aggregator", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					// Secret contains an unrelated key — the required "db-user" is absent.
					"other-key": []byte("other-value"),
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(fixture.capturedBody).To(BeEmpty())

			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonSecretError))
			Expect(ready.Message).To(ContainSubstring(`missing key "db-user"`))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonSecretError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("credentialsSecretRef — all keys present and non-empty (happy path)", func() {
		It("injects Secret values into the aggregator request, sets Phase=Succeeded", func() {
			Expect(k8sClient.Create(ctx, &corev1.Secret{
				ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns},
				Data: map[string][]byte{
					"db-user": []byte("app_user"),
					"db-pass": []byte("s3cr3t"),
				},
			})).To(Succeed())

			spec := baseSpec()
			spec.ConnectionProperties[0].CredentialsSecretRef = &dbaasv1.CredentialsSecretRef{
				Name: secretName,
				Keys: []dbaasv1.SecretKeyMapping{
					{Key: "db-user", Name: "username"},
					{Key: "db-pass", Name: "password"},
				},
			}
			fixture.statusCode = http.StatusOK
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       spec,
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.capturedBody).NotTo(BeEmpty())

			// Verify the credentials were sent to the aggregator.
			var sent struct {
				ConnectionProperties []map[string]string `json:"connectionProperties"`
			}
			Expect(json.Unmarshal(fixture.capturedBody, &sent)).To(Succeed())
			Expect(sent.ConnectionProperties).To(HaveLen(1))
			props := sent.ConnectionProperties[0]
			Expect(props["username"]).To(Equal("app_user"))
			Expect(props["password"]).To(Equal("s3cr3t"))

			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseSucceeded))
			Expect(edb.Status.ObservedGeneration).To(Equal(edb.Generation))

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionTrue))
			Expect(ready.Reason).To(Equal(EventReasonDatabaseRegistered))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeNormal, EventReasonDatabaseRegistered)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Aggregator 4xx errors ─────────────────────────────────────────────────

	Context("HTTP 400 — invalid request (e.g. invalid classifier)", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, emits Warning/AggregatorRejected, does not requeue", func() {
			fixture.statusCode = http.StatusBadRequest
			fixture.body = `{"code":"CORE-DBAAS-4010","reason":"Invalid classifier","message":"Invalid classifier. Classifier does not meet required conditions.","status":"400","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
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

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Invalid classifier. Classifier does not meet required conditions.")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 401 — operator credentials or role binding misconfigured", func() {
		It("sets Phase=BackingOff, Ready=False/Unauthorized, Stalled=False, emits Warning/Unauthorized, requeues", func() {
			fixture.statusCode = http.StatusUnauthorized
			fixture.body = body401Unauthorized
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
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

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonUnauthorized,
				"Requested role is not allowed")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 403 — namespace mismatch between path and classifier", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, emits Warning/AggregatorRejected, does not requeue", func() {
			fixture.statusCode = http.StatusForbidden
			fixture.body = `{"code":"CORE-DBAAS-4004","reason":"Namespace from request is not equal to one from database classifier","message":"Namespace from request is not equal to one from database classifier.","status":"403","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
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

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Namespace from request is not equal to one from database classifier.")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 409 — database exists as internal (not externally manageable)", func() {
		It("sets Phase=InvalidConfiguration, Ready=False/AggregatorRejected, Stalled=True, emits Warning/AggregatorRejected, does not requeue", func() {
			fixture.statusCode = http.StatusConflict
			fixture.body = `{"code":"CORE-DBAAS-4002","reason":"Conflict database request","message":"Conflict database request. Logical database already exists and is not externally manageable.","status":"409","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, result, err := reconcileAndFetch()

			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseInvalidConfiguration))
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

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorRejected,
				"Conflict database request. Logical database already exists and is not externally manageable.")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	// ── Aggregator 5xx / network errors ──────────────────────────────────────

	Context("HTTP 500 — unexpected aggregator server error", func() {
		It("sets Phase=BackingOff, Ready=False/AggregatorError, Stalled=False, emits Warning/AggregatorError, requeues", func() {
			fixture.statusCode = http.StatusInternalServerError
			fixture.body = `{"code":"CORE-DBAAS-2000","reason":"Unexpected exception","message":"Unexpected exception","status":"500","@type":"NC.TMFErrorResponse.v1.0"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
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

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError, "Unexpected exception")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("HTTP 404 — aggregator endpoint mismatch", func() {
		It("treats the response as transient and requeues instead of marking the spec invalid", func() {
			fixture.statusCode = http.StatusNotFound
			fixture.body = `{"message":"endpoint not found"}`
			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred())
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
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

			expectRecordedEventContaining(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError, "endpoint not found")
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})

	Context("Network error — aggregator is unreachable", func() {
		It("sets Phase=BackingOff, Ready=False/AggregatorError, Stalled=False, emits Warning/AggregatorError, requeues", func() {
			// Close the server before reconcile so the HTTP call fails with a
			// connection-refused / EOF error (no AggregatorError wrapping).
			fixture.server.Close()

			Expect(k8sClient.Create(ctx, &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
				Spec:       baseSpec(),
			})).To(Succeed())

			edb, _, err := reconcileAndFetch()

			Expect(err).To(HaveOccurred()) // requeue with backoff
			Expect(edb.Status.Phase).To(Equal(dbaasv1.PhaseBackingOff))
			// Transient error: observedGeneration must NOT be stamped.
			Expect(edb.Status.ObservedGeneration).To(BeZero())

			ready := findCondition(edb.Status.Conditions, conditionTypeReady)
			Expect(ready).NotTo(BeNil())
			Expect(ready.Status).To(Equal(metav1.ConditionFalse))
			Expect(ready.Reason).To(Equal(EventReasonAggregatorError))

			stalled := findCondition(edb.Status.Conditions, conditionTypeStalled)
			Expect(stalled).NotTo(BeNil())
			Expect(stalled.Status).To(Equal(metav1.ConditionFalse))

			expectRecordedEvent(fixture.recorder.Events, corev1.EventTypeWarning, EventReasonAggregatorError)
			expectNoRecordedEvent(fixture.recorder.Events)
		})
	})
})

// ── Ownership requeue behavior ────────────────────────────────────────────────

var _ = Describe("ExternalDatabase Controller — ownership requeue", func() {
	const (
		ns           = "default"
		resourceName = "edb-ownership-test"
	)

	var (
		fixture        *aggregatorSyncFixture
		reconciler     *ExternalDatabaseReconciler
		namespacedName types.NamespacedName
	)

	baseEDB := func() *dbaasv1.ExternalDatabase {
		return &dbaasv1.ExternalDatabase{
			ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns},
			Spec: dbaasv1.ExternalDatabaseSpec{
				Classifier:           dbaasv1.Classifier{Namespace: ns, MicroserviceName: "svc", Scope: "service"},
				Type:                 "postgresql",
				DbName:               "testdb",
				ConnectionProperties: []dbaasv1.ConnectionProperty{{Role: "admin"}},
			},
		}
	}

	BeforeEach(func() {
		fixture = newAggregatorSyncFixture()
		namespacedName = types.NamespacedName{Name: resourceName, Namespace: ns}
		reconciler = &ExternalDatabaseReconciler{
			Client:     k8sClient,
			Scheme:     k8sClient.Scheme(),
			Aggregator: aggregatorclient.NewClientWithTokenFunc(fixture.server.URL, func(_ context.Context) (string, error) { return testToken, nil }),
			Recorder:   fixture.recorder,
		}
	})

	AfterEach(func() {
		fixture.close()
		deleteIfExists(&dbaasv1.ExternalDatabase{ObjectMeta: metav1.ObjectMeta{Name: resourceName, Namespace: ns}})
	})

	Context("when no NamespaceBinding exists (Unbound state after first reconcile)", func() {
		It("returns ownershipUnboundRetryInterval as a lost-trigger safety net", func() {
			reconciler.Ownership = emptyOwnershipResolver()
			Expect(k8sClient.Create(ctx, baseEDB())).To(Succeed())

			_, result, err := reconcileAndFetchObject(reconciler, namespacedName,
				func() *dbaasv1.ExternalDatabase { return &dbaasv1.ExternalDatabase{} })
			Expect(err).NotTo(HaveOccurred())
			// Requeue at the long Unbound interval so that if the NamespaceBinding →
			// workloads watch fan-out loses its trigger the CR is eventually retried.
			Expect(result.RequeueAfter).To(Equal(ownershipUnboundRetryInterval))
			// No aggregator call should have been made.
			Expect(fixture.capturedBody).To(BeEmpty())
		})
	})

	Context("when the namespace belongs to a different operator (Foreign state)", func() {
		It("returns an empty Result (no requeue needed — watch will fire on binding change)", func() {
			reconciler.Ownership = foreignOwnershipResolver(ns)
			Expect(k8sClient.Create(ctx, baseEDB())).To(Succeed())

			_, result, err := reconcileAndFetchObject(reconciler, namespacedName,
				func() *dbaasv1.ExternalDatabase { return &dbaasv1.ExternalDatabase{} })
			Expect(err).NotTo(HaveOccurred())
			Expect(result.RequeueAfter).To(BeZero())
			Expect(fixture.capturedBody).To(BeEmpty())
		})
	})

	Context("enqueueForBinding — fan-out mapping", func() {
		It("returns a reconcile.Request for every ExternalDatabase in the namespace", func() {
			reconciler.Ownership = mineOwnershipResolver(ns)

			edb1 := baseEDB()
			edb2 := baseEDB()
			edb2.Name = resourceName + "-2"
			Expect(k8sClient.Create(ctx, edb1)).To(Succeed())
			Expect(k8sClient.Create(ctx, edb2)).To(Succeed())
			DeferCleanup(func() {
				deleteIfExists(&dbaasv1.ExternalDatabase{ObjectMeta: metav1.ObjectMeta{Name: edb2.Name, Namespace: ns}})
			})

			ob := &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBindingName, Namespace: ns},
			}
			reqs := reconciler.enqueueForBinding(ctx, ob)

			names := make([]string, 0, len(reqs))
			for _, r := range reqs {
				names = append(names, r.Name)
			}
			Expect(names).To(ConsistOf(edb1.Name, edb2.Name))
		})

		It("returns an empty slice when no ExternalDatabases exist in the namespace", func() {
			reconciler.Ownership = mineOwnershipResolver(ns)
			ob := &dbaasv1.NamespaceBinding{
				ObjectMeta: metav1.ObjectMeta{Name: dbaasv1.NamespaceBindingName, Namespace: "empty-ns"},
			}
			reqs := reconciler.enqueueForBinding(ctx, ob)
			Expect(reqs).To(BeEmpty())
		})
	})

	Context("indexSecretNames — index key extraction", func() {
		It("returns all secret names referenced by the EDB", func() {
			edb := baseEDB()
			edb.Spec.ConnectionProperties = []dbaasv1.ConnectionProperty{
				{
					Role: "admin",
					CredentialsSecretRef: &dbaasv1.CredentialsSecretRef{
						Name: "secret-a",
						Keys: []dbaasv1.SecretKeyMapping{{Key: "user", Name: "username"}},
					},
				},
				{
					Role: "ro",
					CredentialsSecretRef: &dbaasv1.CredentialsSecretRef{
						Name: "secret-b",
						Keys: []dbaasv1.SecretKeyMapping{{Key: "user", Name: "username"}},
					},
				},
			}
			Expect(indexSecretNames(edb)).To(ConsistOf("default/secret-a", "default/secret-b"))
		})

		It("returns nil when no connectionProperty has a credentialsSecretRef", func() {
			edb := baseEDB()
			// baseEDB has no credentialsSecretRef set.
			Expect(indexSecretNames(edb)).To(BeNil())
		})

		It("returns nil for a non-ExternalDatabase object", func() {
			Expect(indexSecretNames(&dbaasv1.NamespaceBinding{})).To(BeNil())
		})
	})
})

// ── Rate limiter / SetupWithManager ───────────────────────────────────────────

var _ = Describe("ExternalDatabase Controller — rate limiter", func() {
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
		// SkipNameValidation avoids "controller already exists" errors when multiple
		// test suites register the same controller name in the same process.
		skipValidation := true
		mgr, err := ctrl.NewManager(cfg, ctrl.Options{
			Scheme:                 k8sClient.Scheme(),
			Metrics:                httpserver.Options{BindAddress: "0"},
			HealthProbeBindAddress: "0",
			Controller:             config.Controller{SkipNameValidation: &skipValidation},
		})
		Expect(err).NotTo(HaveOccurred())

		const base = 100 * time.Millisecond
		const max = 10 * time.Second

		rateLimiter := workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](base, max)

		err = (&ExternalDatabaseReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Recorder:   mgr.GetEventRecorderFor("edb-rate-limiter-test"), //nolint:staticcheck
			Aggregator: aggregatorclient.NewClientWithTokenFunc("http://localhost:9999", func(_ context.Context) (string, error) { return testToken, nil }),
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

// ── Secret watch integration ───────────────────────────────────────────────────

var _ = Describe("ExternalDatabase Controller — secret watch", func() {
	// enqueueForSecret requires the field index to be registered and the cache
	// to be populated, so it cannot be tested with a bare reconciler.  This suite
	// spins up a real manager (same envtest API server) so that SetupWithManager
	// registers the index and the cache warms up before we call enqueueForSecret.

	const (
		ns         = "default"
		secretName = "sw-test-secret"
		edbName    = "sw-test-edb"
	)

	var (
		mgr        ctrl.Manager
		reconciler *ExternalDatabaseReconciler
		cancel     context.CancelFunc
		done       chan struct{}
	)

	BeforeEach(func() {
		var mgrCtx context.Context
		mgrCtx, cancel = context.WithCancel(context.Background())

		// SkipNameValidation avoids "controller already exists" errors when the
		// rate-limiter test suite registers the same controller name in the same process.
		// GracefulShutdownTimeout caps how long AfterEach waits for mgr.Start to return.
		skipValidation := true
		shutdownTimeout := 5 * time.Second
		var err error
		mgr, err = ctrl.NewManager(cfg, ctrl.Options{
			Scheme:                  k8sClient.Scheme(),
			Metrics:                 httpserver.Options{BindAddress: "0"},
			HealthProbeBindAddress:  "0",
			Controller:              config.Controller{SkipNameValidation: &skipValidation},
			GracefulShutdownTimeout: &shutdownTimeout,
		})
		Expect(err).NotTo(HaveOccurred())

		reconciler = &ExternalDatabaseReconciler{
			Client:     mgr.GetClient(),
			Scheme:     mgr.GetScheme(),
			Recorder:   mgr.GetEventRecorderFor("sw-test"), //nolint:staticcheck
			Aggregator: aggregatorclient.NewClientWithTokenFunc("http://localhost:9999", func(_ context.Context) (string, error) { return testToken, nil }),
			Ownership:  mineOwnershipResolver(ns),
		}
		Expect(reconciler.SetupWithManager(mgr, ctrlcontroller.Options{})).To(Succeed())

		// done channel signals when mgr.Start has fully returned. AfterEach
		// waits on it to prevent the previous manager from racing with the
		// next test (informers, in-flight reconciles, API calls).
		done = make(chan struct{})
		go func() {
			defer close(done)
			_ = mgr.Start(mgrCtx)
		}()
		// Wait for the cache to sync before any test interacts with the index.
		Expect(mgr.GetCache().WaitForCacheSync(mgrCtx)).To(BeTrue())
	})

	AfterEach(func() {
		cancel()
		// Block until mgr.Start fully returns: ensures informers, controllers,
		// and any in-flight reconciles have shut down before the next test starts.
		<-done
		deleteIfExists(&dbaasv1.ExternalDatabase{ObjectMeta: metav1.ObjectMeta{Name: edbName, Namespace: ns}})
		deleteIfExists(&corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns}})
	})

	It("enqueueForSecret returns a request for an EDB that references the changed secret", func() {
		edb := &dbaasv1.ExternalDatabase{
			ObjectMeta: metav1.ObjectMeta{Name: edbName, Namespace: ns},
			Spec: dbaasv1.ExternalDatabaseSpec{
				Classifier: dbaasv1.Classifier{MicroserviceName: "svc", Scope: "service", Namespace: ns},
				Type:       "postgresql",
				DbName:     "testdb",
				ConnectionProperties: []dbaasv1.ConnectionProperty{
					{
						Role: "admin",
						CredentialsSecretRef: &dbaasv1.CredentialsSecretRef{
							Name: secretName,
							Keys: []dbaasv1.SecretKeyMapping{{Key: "password", Name: "password"}},
						},
					},
				},
			},
		}
		Expect(mgr.GetClient().Create(ctx, edb)).To(Succeed())

		// The cache index is populated asynchronously — wait for it to reflect the new EDB.
		secretObj := &corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns}}
		Eventually(func() []reconcile.Request {
			return reconciler.enqueueForSecret(ctx, secretObj)
		}).Should(HaveLen(1))

		reqs := reconciler.enqueueForSecret(ctx, secretObj)
		Expect(reqs[0].NamespacedName).To(Equal(types.NamespacedName{Name: edbName, Namespace: ns}))
	})

	It("enqueueForSecret returns empty when no EDB references the secret", func() {
		secretObj := &corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: "unrelated-secret", Namespace: ns}}
		reqs := reconciler.enqueueForSecret(ctx, secretObj)
		Expect(reqs).To(BeEmpty())
	})

	It("enqueueForSecret returns requests for all EDBs that reference the same secret", func() {
		const edbName2 = "sw-test-edb-2"
		mkEDB := func(name string) *dbaasv1.ExternalDatabase {
			return &dbaasv1.ExternalDatabase{
				ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: ns},
				Spec: dbaasv1.ExternalDatabaseSpec{
					Classifier: dbaasv1.Classifier{MicroserviceName: name, Scope: "service", Namespace: ns},
					Type:       "postgresql",
					DbName:     name + "-db",
					ConnectionProperties: []dbaasv1.ConnectionProperty{
						{
							Role: "admin",
							CredentialsSecretRef: &dbaasv1.CredentialsSecretRef{
								Name: secretName, // both EDBs share the same Secret
								Keys: []dbaasv1.SecretKeyMapping{{Key: "password", Name: "password"}},
							},
						},
					},
				},
			}
		}
		Expect(mgr.GetClient().Create(ctx, mkEDB(edbName))).To(Succeed())
		Expect(mgr.GetClient().Create(ctx, mkEDB(edbName2))).To(Succeed())
		// AfterEach removes edbName; clean up the second one here.
		DeferCleanup(func() {
			deleteIfExists(&dbaasv1.ExternalDatabase{ObjectMeta: metav1.ObjectMeta{Name: edbName2, Namespace: ns}})
		})

		// Wait for the cache index to reflect both EDBs.
		secretObj := &corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: ns}}
		Eventually(func() []reconcile.Request {
			return reconciler.enqueueForSecret(ctx, secretObj)
		}).Should(HaveLen(2))

		reqs := reconciler.enqueueForSecret(ctx, secretObj)
		names := []string{reqs[0].Name, reqs[1].Name}
		Expect(names).To(ConsistOf(edbName, edbName2))
		for _, r := range reqs {
			Expect(r.Namespace).To(Equal(ns))
		}
	})
})
