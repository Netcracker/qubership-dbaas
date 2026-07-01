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

package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// newTestHandler builds the mock handler with no per-key rules except the given
// create-db rules, mirroring how the real mock is wired in main().
func newTestHandler(defaultCode int, createDbRules map[string]MockRule) http.Handler {
	return handler(MockRule{HTTPCode: defaultCode}, nil, nil, nil, nil, nil, createDbRules)
}

// tenantCreateBody builds a get-or-create request body for a tenant-scoped database,
// keeping the test lines short (the inline JSON literal would otherwise trip the
// line-length linter).
func tenantCreateBody(t *testing.T, microservice, tenant string) string {
	t.Helper()
	classifier := map[string]any{
		"microserviceName": microservice,
		"scope":            "tenant",
		"namespace":        "test-ns",
	}
	if tenant != "" {
		classifier["tenantId"] = tenant
	}
	b, err := json.Marshal(map[string]any{
		"classifier":    classifier,
		"type":          "postgresql",
		"originService": microservice,
	})
	if err != nil {
		t.Fatalf("marshal body: %v", err)
	}
	return string(b)
}

const gbcPath = "/api/v3/dbaas/test-ns/databases/get-by-classifier/postgresql"

// gbcReqBody builds a get-by-classifier request body for the given classifier.
func gbcReqBody(t *testing.T, classifier map[string]any, origin string) string {
	t.Helper()
	b, err := json.Marshal(map[string]any{
		"classifier":    classifier,
		"originService": origin,
		"userRole":      "admin",
	})
	if err != nil {
		t.Fatalf("marshal gbc body: %v", err)
	}
	return string(b)
}

func doReq(t *testing.T, h http.Handler, method, path, body string, withAuth bool) *httptest.ResponseRecorder {
	t.Helper()
	var r *http.Request
	if body != "" {
		r = httptest.NewRequest(method, path, bytes.NewReader([]byte(body)))
	} else {
		r = httptest.NewRequest(method, path, nil)
	}
	if withAuth {
		r.SetBasicAuth("operator", "x")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

// TestCreateDatabase_DefaultSuccess: a tenant get-or-create with no rule returns 200
// and a well-formed descriptor that echoes the namespace, type, and pinned tenantId.
func TestCreateDatabase_DefaultSuccess(t *testing.T) {
	h := newTestHandler(http.StatusOK, nil)
	body := tenantCreateBody(t, "quarkus-test-app-service", "acme")

	w := doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases", body, true)
	if w.Code != http.StatusOK {
		t.Fatalf("want 200, got %d (%s)", w.Code, w.Body.String())
	}

	var resp struct {
		Name      string `json:"name"`
		Namespace string `json:"namespace"`
		Type      string `json:"type"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if resp.Namespace != "test-ns" {
		t.Errorf("namespace: want test-ns, got %q", resp.Namespace)
	}
	if resp.Type != "postgresql" {
		t.Errorf("type: want postgresql, got %q", resp.Type)
	}
	if !strings.Contains(resp.Name, "acme") {
		t.Errorf("expected tenantId in db name, got %q", resp.Name)
	}
}

// TestCreateDatabase_RequiresAuth: an unauthenticated get-or-create is rejected with 401.
func TestCreateDatabase_RequiresAuth(t *testing.T) {
	h := newTestHandler(http.StatusOK, nil)
	w := doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases", `{"type":"postgresql"}`, false)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", w.Code)
	}
}

// TestCreateDatabase_ErrorRule: a per-originService error rule is returned as a TMF error,
// letting the dev environment simulate a materialization failure.
func TestCreateDatabase_ErrorRule(t *testing.T) {
	rules := map[string]MockRule{
		"failing-svc": {HTTPCode: http.StatusInternalServerError, TmfCode: "CORE-DBAAS-5000", Message: "boom"},
	}
	h := newTestHandler(http.StatusOK, rules)
	body := tenantCreateBody(t, "failing-svc", "acme")

	w := doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases", body, true)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("want 500, got %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), "boom") {
		t.Errorf("expected TMF message in body, got %s", w.Body.String())
	}
}

// TestCreateDatabase_RouteIsolation: the bare /databases route must not collide with the
// longer .../databases/registration/externally_manageable route, and only PUT is accepted.
func TestCreateDatabase_RouteIsolation(t *testing.T) {
	// defaultCode 201 lets us tell the two PUT handlers apart: external-db honours the
	// default (201); create-db has its own fixed 200 default independent of it.
	h := newTestHandler(http.StatusCreated, nil)

	wExt := doReq(t, h, http.MethodPut,
		"/api/v3/dbaas/test-ns/databases/registration/externally_manageable",
		`{"dbName":"x"}`, true)
	if wExt.Code != http.StatusCreated {
		t.Errorf("externally_manageable: want 201 (external-db handler), got %d", wExt.Code)
	}

	wCreate := doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases",
		`{"type":"postgresql","originService":"svc"}`, true)
	if wCreate.Code != http.StatusOK {
		t.Errorf("create-db: want 200 (create-db handler), got %d", wCreate.Code)
	}

	wGet := doReq(t, h, http.MethodGet, "/api/v3/dbaas/test-ns/databases", "", true)
	if wGet.Code != http.StatusNotFound {
		t.Errorf("GET /databases (wrong method): want 404, got %d", wGet.Code)
	}
}

// TestGetByClassifier_TenantRequiresMaterialization is the dependency the kind e2e relies on:
// a tenant database is 404 (DatabaseNotFound) until it is materialized via the get-or-create
// call, after which the same classifier resolves with 200. This is exactly the gap the
// operator's pinned-tenant materialization closes for a DatabaseSecretClaim.
func TestGetByClassifier_TenantRequiresMaterialization(t *testing.T) {
	h := newTestHandler(http.StatusOK, nil)
	classifier := map[string]any{
		"microserviceName": "idb-tenant",
		"scope":            "tenant",
		"tenantId":         "acme",
		"namespace":        "test-ns",
	}

	// Before materialization → 404.
	w := doReq(t, h, http.MethodPost, gbcPath, gbcReqBody(t, classifier, "idb-tenant"), true)
	if w.Code != http.StatusNotFound {
		t.Fatalf("pre-materialize: want 404, got %d (%s)", w.Code, w.Body.String())
	}

	// Materialize the same {classifier, type} via the get-or-create call.
	wc := doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases",
		tenantCreateBody(t, "idb-tenant", "acme"), true)
	if wc.Code != http.StatusOK {
		t.Fatalf("create: want 200, got %d", wc.Code)
	}

	// After materialization → 200.
	w2 := doReq(t, h, http.MethodPost, gbcPath, gbcReqBody(t, classifier, "idb-tenant"), true)
	if w2.Code != http.StatusOK {
		t.Fatalf("post-materialize: want 200, got %d (%s)", w2.Code, w2.Body.String())
	}
}

// TestGetByClassifier_ServiceNoMaterializationNeeded asserts the materialization gate is
// tenant-only: a service classifier resolves with 200 without any prior get-or-create,
// preserving the behavior the existing service-scoped dev scenarios depend on.
func TestGetByClassifier_ServiceNoMaterializationNeeded(t *testing.T) {
	h := newTestHandler(http.StatusOK, nil)
	classifier := map[string]any{
		"microserviceName": "svc",
		"scope":            "service",
		"namespace":        "test-ns",
	}
	w := doReq(t, h, http.MethodPost, gbcPath, gbcReqBody(t, classifier, "svc"), true)
	if w.Code != http.StatusOK {
		t.Fatalf("service get-by-classifier: want 200 (no materialization needed), got %d", w.Code)
	}
}
