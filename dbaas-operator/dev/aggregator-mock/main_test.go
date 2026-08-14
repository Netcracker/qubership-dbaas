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

// tenantCreateBody builds a get-or-create request body for a tenant-scoped postgresql
// database. An empty tenant leaves tenantId out of the classifier.
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

// gbcReqBody builds a get-by-classifier request body for the given classifier,
// always with userRole "admin".
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

// TestCreateDatabase_PendingCalls_Returns202ThenCreates reproduces the aggregator's asynchronous
// creation: while a rule's pendingCalls budget lasts, get-or-create answers 202 with no id, no
// name, and a null password, and only the call past the budget returns the created database.
// A client that treats the 202 as either success or failure never gets credentials.
func TestCreateDatabase_PendingCalls_Returns202ThenCreates(t *testing.T) {
	rules := map[string]MockRule{"slow-svc": {HTTPCode: http.StatusOK, PendingCalls: 2}}
	h := newTestHandler(http.StatusOK, rules)
	body := tenantCreateBody(t, "slow-svc", "acme")
	const path = "/api/v3/dbaas/test-ns/databases"

	for attempt := 1; attempt <= 2; attempt++ {
		w := doReq(t, h, http.MethodPut, path, body, true)
		if w.Code != http.StatusAccepted {
			t.Fatalf("attempt %d: want 202, got %d (%s)", attempt, w.Code, w.Body.String())
		}

		var resp struct {
			ID                   any `json:"id"`
			Name                 any `json:"name"`
			ConnectionProperties struct {
				Password any    `json:"password"`
				Role     string `json:"role"`
			} `json:"connectionProperties"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("attempt %d: decode body: %v", attempt, err)
		}
		if resp.ID != nil || resp.Name != nil {
			t.Errorf("attempt %d: a database being created has no id/name, got id=%v name=%v",
				attempt, resp.ID, resp.Name)
		}
		if resp.ConnectionProperties.Password != nil {
			t.Errorf("attempt %d: 202 must not carry a password, got %v",
				attempt, resp.ConnectionProperties.Password)
		}
		if resp.ConnectionProperties.Role != "admin" {
			t.Errorf("attempt %d: role: want admin, got %q", attempt, resp.ConnectionProperties.Role)
		}
	}

	// The third call is past the pending budget: the database is created for real.
	w := doReq(t, h, http.MethodPut, path, body, true)
	if w.Code != http.StatusOK {
		t.Fatalf("third attempt: want 200, got %d (%s)", w.Code, w.Body.String())
	}
	var created struct {
		Name                 string `json:"name"`
		ConnectionProperties struct {
			Password string `json:"password"`
		} `json:"connectionProperties"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode created body: %v", err)
	}
	if created.ConnectionProperties.Password == "" {
		t.Error("the created database must carry a password")
	}
	if !strings.Contains(created.Name, "acme") {
		t.Errorf("expected tenantId in db name, got %q", created.Name)
	}
}

// TestCreateDatabase_PendingCalls_NotVisibleUntilCreated asserts that a database still being
// created is not resolvable through get-by-classifier — the state a DatabaseSecretClaim sees while
// the operator waits on the 202.
func TestCreateDatabase_PendingCalls_NotVisibleUntilCreated(t *testing.T) {
	rules := map[string]MockRule{"slow-svc": {HTTPCode: http.StatusOK, PendingCalls: 1}}
	h := newTestHandler(http.StatusOK, rules)
	classifier := map[string]any{
		"microserviceName": "slow-svc",
		"scope":            "tenant",
		"namespace":        "test-ns",
		"tenantId":         "acme",
	}

	w := doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases",
		tenantCreateBody(t, "slow-svc", "acme"), true)
	if w.Code != http.StatusAccepted {
		t.Fatalf("want 202, got %d", w.Code)
	}

	w = doReq(t, h, http.MethodPost, gbcPath, gbcReqBody(t, classifier, "slow-svc"), true)
	if w.Code == http.StatusOK {
		t.Fatalf("a database that is still being created must not resolve, got 200: %s", w.Body.String())
	}

	// After the creation completes it resolves normally.
	if w = doReq(t, h, http.MethodPut, "/api/v3/dbaas/test-ns/databases",
		tenantCreateBody(t, "slow-svc", "acme"), true); w.Code != http.StatusOK {
		t.Fatalf("second create: want 200, got %d", w.Code)
	}
	if w = doReq(t, h, http.MethodPost, gbcPath, gbcReqBody(t, classifier, "slow-svc"), true); w.Code != http.StatusOK {
		t.Fatalf("after creation get-by-classifier must return 200, got %d (%s)", w.Code, w.Body.String())
	}
}
