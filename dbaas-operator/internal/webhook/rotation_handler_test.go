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

package webhook

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

// stubAuthenticator lets handler tests bypass real OIDC validation. The
// handler only consumes the AuthResult and the error sentinel, so a thin
// stub suffices.
type stubAuthenticator struct {
	result AuthResult
	err    error

	// gotAuthHeader captures the value forwarded into Authenticate.
	gotAuthHeader string
}

func (s *stubAuthenticator) Authenticate(_ context.Context, authHeader string) (AuthResult, error) {
	s.gotAuthHeader = authHeader
	return s.result, s.err
}

// newFakeClient builds a controller-runtime fake client that knows about the
// DatabaseSecretClaim type and has ClassifierTypeIndex registered, mirroring the
// production manager setup. Initial objects are persisted in-memory so the
// handler's List queries return them.
func newFakeClient(initial ...client.Object) client.Client {
	scheme := runtime.NewScheme()
	Expect(dbaasv1.AddToScheme(scheme)).To(Succeed())
	builder := fake.NewClientBuilder().
		WithScheme(scheme).
		WithIndex(&dbaasv1.DatabaseSecretClaim{}, dbaasv1.ClassifierTypeIndex,
			func(obj client.Object) []string {
				ds := obj.(*dbaasv1.DatabaseSecretClaim)
				c := dbaasv1.EffectiveClassifier(ds.Spec.Classifier, ds.Namespace)
				return []string{dbaasv1.ClassifierIndexKey(c, ds.Spec.Type)}
			})
	if len(initial) > 0 {
		builder = builder.WithObjects(initial...)
	}
	return builder.Build()
}

// testDBType is the database engine the rotation handler tests use; the
// handler is agnostic to the actual value, but pinning to one in helpers
// keeps test setups concise.
const testDBType = "postgresql"

// buildPayloadBody marshals a payload to JSON for use as an HTTP request body.
func buildPayloadBody(eventID, namespace, microserviceName string) []byte {
	body, err := json.Marshal(map[string]any{
		"eventId":    eventID,
		"occurredAt": "2026-05-25T10:00:00Z",
		"classifier": map[string]any{
			"microserviceName": microserviceName,
			"scope":            "service",
			"namespace":        namespace,
		},
		"type":      testDBType,
		"userRole":  "admin",
		"eventType": EventTypeRotationOccurred,
	})
	Expect(err).NotTo(HaveOccurred())
	return body
}

// newDatabaseSecretClaim constructs a minimal DatabaseSecretClaim CR for fake-client
// seeding.
func newDatabaseSecretClaim(name, namespace, microserviceName string) *dbaasv1.DatabaseSecretClaim {
	return &dbaasv1.DatabaseSecretClaim{
		ObjectMeta: metav1.ObjectMeta{Name: name, Namespace: namespace},
		Spec: dbaasv1.DatabaseSecretClaimSpec{
			Classifier: dbaasv1.Classifier{
				MicroserviceName: microserviceName,
				Scope:            "service",
				Namespace:        namespace,
			},
			Type:       testDBType,
			SecretName: name + "-secret",
		},
	}
}

var _ = Describe("RotationHandler", func() {
	var (
		auth       *stubAuthenticator
		fakeClient client.Client
		handler    *RotationHandler
	)

	BeforeEach(func() {
		auth = &stubAuthenticator{result: AuthResult{
			Subject:        "system:serviceaccount:dbaas:dbaas-aggregator",
			Namespace:      "dbaas",
			ServiceAccount: "dbaas-aggregator",
		}}
		// Tests that need CRs override fakeClient via newFakeClient(...).
		fakeClient = newFakeClient()
		handler = &RotationHandler{
			Client: fakeClient,
			Auth:   auth,
			// Allow the stub's subject so authorized-path tests reach the handler
			// body. Authorization-specific tests override this set explicitly.
			AllowedSubjects: map[string]struct{}{
				"system:serviceaccount:dbaas:dbaas-aggregator": {},
			},
		}
	})

	Describe("HTTP method handling", func() {
		It("returns 405 for GET", func() {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, PathRotationNotify, nil)

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusMethodNotAllowed))
			Expect(rec.Header().Get("Allow")).To(Equal(http.MethodPost))
			Expect(auth.gotAuthHeader).To(BeEmpty(), "auth must not run for non-POST methods")
		})

		It("returns 405 for PUT", func() {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPut, PathRotationNotify, nil)

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusMethodNotAllowed))
		})
	})

	Describe("Authentication", func() {
		It("returns 401 when the authenticator rejects the token", func() {
			auth.err = errors.New("token expired")
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-a")))
			req.Header.Set("Authorization", "Bearer bad")

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusUnauthorized))
			Expect(auth.gotAuthHeader).To(Equal("Bearer bad"))
		})

		It("forwards the Authorization header verbatim to the authenticator", func() {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-a")))
			req.Header.Set("Authorization", "Bearer some-token")

			handler.ServeHTTP(rec, req)

			Expect(auth.gotAuthHeader).To(Equal("Bearer some-token"))
		})
	})

	Describe("Authorization", func() {
		It("returns 403 when the authenticated subject is not in the allow-list", func() {
			// Valid token, but a subject the operator does not trust.
			auth.result = AuthResult{
				Subject:        "system:serviceaccount:other-ns:rogue-sa",
				Namespace:      "other-ns",
				ServiceAccount: "rogue-sa",
			}
			// Seed a CR that would match the payload, to prove the body is never
			// processed for an unauthorized caller.
			crA := newDatabaseSecretClaim("cr-a", "team-a", "svc-a")
			fakeClient = newFakeClient(crA)
			handler.Client = fakeClient

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-a")))
			req.Header.Set("Authorization", "Bearer good-but-unauthorized")

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusForbidden))
			// The matching CR must NOT have been patched.
			got := &dbaasv1.DatabaseSecretClaim{}
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-a", Name: "cr-a"}, got)).To(Succeed())
			Expect(got.Annotations).NotTo(HaveKey(dbaasv1.AnnotationRotationTrigger))
		})

		It("returns 403 for every caller when the allow-list is empty (fail-closed)", func() {
			handler.AllowedSubjects = nil // even a valid, well-known subject is denied

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-a")))
			req.Header.Set("Authorization", "Bearer good")

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusForbidden))
		})

		It("accepts a subject present alongside others in a multi-entry allow-list", func() {
			handler.AllowedSubjects = map[string]struct{}{
				"system:serviceaccount:dbaas:some-other-sa":    {},
				"system:serviceaccount:dbaas:dbaas-aggregator": {}, // the stub's subject
				"system:serviceaccount:elsewhere:another-sa":   {},
			}

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-a")))
			req.Header.Set("Authorization", "Bearer good")

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusOK))
		})
	})

	Describe("Payload validation", func() {
		assertBadRequest := func(body []byte) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify, bytes.NewReader(body))
			handler.ServeHTTP(rec, req)
			Expect(rec.Code).To(Equal(http.StatusBadRequest))
		}

		It("returns 400 on malformed JSON", func() {
			assertBadRequest([]byte("{not-json"))
		})

		It("returns 400 when eventId is missing", func() {
			body, _ := json.Marshal(map[string]any{
				"type":       "postgresql",
				"classifier": map[string]any{"microserviceName": "x", "scope": "service", "namespace": "team-a"},
			})
			assertBadRequest(body)
		})

		It("returns 400 when type is missing", func() {
			body, _ := json.Marshal(map[string]any{
				"eventId":    "evt-1",
				"classifier": map[string]any{"microserviceName": "x", "scope": "service", "namespace": "team-a"},
			})
			assertBadRequest(body)
		})

		It("returns 400 when classifier is missing", func() {
			body, _ := json.Marshal(map[string]any{
				"eventId": "evt-1",
				"type":    "postgresql",
			})
			assertBadRequest(body)
		})

		It("returns 400 when classifier.namespace is missing", func() {
			body, _ := json.Marshal(map[string]any{
				"eventId":    "evt-1",
				"type":       "postgresql",
				"classifier": map[string]any{"microserviceName": "x", "scope": "service"},
			})
			assertBadRequest(body)
		})

		It("returns 400 when classifier.namespace is not a string", func() {
			body, _ := json.Marshal(map[string]any{
				"eventId":    "evt-1",
				"type":       "postgresql",
				"classifier": map[string]any{"microserviceName": "x", "scope": "service", "namespace": 42},
			})
			assertBadRequest(body)
		})
	})

	Describe("CR resolution and annotation patch", func() {
		It("patches matching CRs and returns the counts", func() {
			crA := newDatabaseSecretClaim("cr-a", "team-a", "svc-a")
			crB := newDatabaseSecretClaim("cr-b", "team-a", "svc-a")
			crOther := newDatabaseSecretClaim("cr-other", "team-a", "svc-different")
			fakeClient = newFakeClient(crA, crB, crOther)
			handler.Client = fakeClient

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-42", "team-a", "svc-a")))
			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusOK))
			var resp rotationResponse
			Expect(json.Unmarshal(rec.Body.Bytes(), &resp)).To(Succeed())
			Expect(resp.Matched).To(Equal(2))
			Expect(resp.Patched).To(Equal(2))

			// Annotation present on the two matches.
			got := &dbaasv1.DatabaseSecretClaim{}
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-a", Name: "cr-a"}, got)).To(Succeed())
			Expect(got.Annotations).To(HaveKeyWithValue(dbaasv1.AnnotationRotationTrigger, "evt-42"))
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-a", Name: "cr-b"}, got)).To(Succeed())
			Expect(got.Annotations).To(HaveKeyWithValue(dbaasv1.AnnotationRotationTrigger, "evt-42"))

			// The non-matching CR must NOT have the annotation.
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-a", Name: "cr-other"}, got)).To(Succeed())
			Expect(got.Annotations).NotTo(HaveKey(dbaasv1.AnnotationRotationTrigger))
		})

		It("returns matched=0, patched=0 when no CR matches", func() {
			fakeClient = newFakeClient() // no CRs
			handler.Client = fakeClient

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-unknown")))
			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusOK))
			var resp rotationResponse
			Expect(json.Unmarshal(rec.Body.Bytes(), &resp)).To(Succeed())
			Expect(resp.Matched).To(BeZero())
			Expect(resp.Patched).To(BeZero())
		})

		It("scopes the lookup to the classifier's namespace", func() {
			// Same (microserviceName, scope, type) in two different namespaces.
			crSameNs := newDatabaseSecretClaim("cr-x", "team-a", "svc-a")
			crDifferentNs := newDatabaseSecretClaim("cr-x", "team-b", "svc-a")
			fakeClient = newFakeClient(crSameNs, crDifferentNs)
			handler.Client = fakeClient

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-7", "team-a", "svc-a")))
			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusOK))
			var resp rotationResponse
			Expect(json.Unmarshal(rec.Body.Bytes(), &resp)).To(Succeed())
			Expect(resp.Matched).To(Equal(1),
				"only the CR in the classifier's namespace should match")

			got := &dbaasv1.DatabaseSecretClaim{}
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-b", Name: "cr-x"}, got)).To(Succeed())
			Expect(got.Annotations).NotTo(HaveKey(dbaasv1.AnnotationRotationTrigger),
				"CRs outside the classifier's namespace must not be touched")
		})

		It("uses the namespace from the payload, not a hard-coded one", func() {
			// Same setup as above but the payload now targets team-b, so the
			// roles are swapped — guards against a bug where the handler
			// accidentally hard-codes the namespace.
			crA := newDatabaseSecretClaim("cr-x", "team-a", "svc-a")
			crB := newDatabaseSecretClaim("cr-x", "team-b", "svc-a")
			fakeClient = newFakeClient(crA, crB)
			handler.Client = fakeClient

			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-8", "team-b", "svc-a")))
			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusOK))
			got := &dbaasv1.DatabaseSecretClaim{}
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-b", Name: "cr-x"}, got)).To(Succeed())
			Expect(got.Annotations).To(HaveKeyWithValue(dbaasv1.AnnotationRotationTrigger, "evt-8"))
			Expect(fakeClient.Get(context.Background(),
				client.ObjectKey{Namespace: "team-a", Name: "cr-x"}, got)).To(Succeed())
			Expect(got.Annotations).NotTo(HaveKey(dbaasv1.AnnotationRotationTrigger))
		})
	})

	Describe("Request ID propagation", func() {
		It("uses an incoming X-Request-Id header verbatim", func() {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, PathRotationNotify,
				bytes.NewReader(buildPayloadBody("evt-1", "team-a", "svc-a")))
			req.Header.Set("X-Request-Id", "external-trace-abc")

			handler.ServeHTTP(rec, req)

			Expect(rec.Code).To(Equal(http.StatusOK))
			// Indirect check — handler does not echo it back, but completion
			// without panic confirms ctxmanager.InitContext accepted the value.
		})
	})
})
