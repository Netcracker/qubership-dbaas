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

package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/requestcontext"
)

// ── helpers ───────────────────────────────────────────────────────────────────

// dbTypePostgresql is the database type used throughout these client tests.
const dbTypePostgresql = "postgresql"

func TestMain(m *testing.M) {
	requestcontext.RegisterProviders()
	os.Exit(m.Run())
}

func contextWithRequestID(requestID string) context.Context {
	return ctxmanager.InitContext(context.Background(), map[string]any{
		xrequestid.X_REQUEST_ID_HEADER_NAME: requestID,
	})
}

func requestContext() context.Context {
	return contextWithRequestID("client-test-request-id")
}

// staticToken returns a getToken function that always returns the given token, so
// a test never touches the global tokensource state.
func staticToken(token string) func(context.Context) (string, error) {
	return func(context.Context) (string, error) { return token, nil }
}

// minimalExtDBRequest returns the smallest valid ExternalDatabaseRequest.
func minimalExtDBRequest() *ExternalDatabaseRequest {
	return &ExternalDatabaseRequest{
		Classifier:           map[string]any{"namespace": "test"},
		Type:                 dbTypePostgresql,
		DBName:               "test-db",
		ConnectionProperties: []map[string]string{{"role": "admin"}},
	}
}

// minimalDeclarativePayload returns the smallest valid DeclarativePayload.
func minimalDeclarativePayload() *DeclarativePayload {
	return &DeclarativePayload{
		APIVersion: "v1",
		Kind:       "Database",
		SubKind:    "DatabaseDeclaration",
		Metadata: DeclarativeMeta{
			Name:             "test-db",
			Namespace:        "test-ns",
			MicroserviceName: "test-service",
		},
		Spec: map[string]string{"type": dbTypePostgresql},
	}
}

// writeJSON marshals v and writes it as an application/json response.
func writeJSON(t *testing.T, w http.ResponseWriter, status int, v any) {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("writeJSON marshal: %v", err)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(b)
}

// bearerToken extracts the Bearer token from the Authorization header.
func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	return strings.TrimPrefix(h, "Bearer ")
}

func TestAggregatorClient_PropagatesRequestID(t *testing.T) {
	const requestID = "operator-reconcile-request-id"
	tests := []struct {
		name           string
		newClient      func(string) *AggregatorClient
		wantAuthPrefix string
	}{
		{
			name: "M2M",
			newClient: func(baseURL string) *AggregatorClient {
				return newClient(baseURL, staticToken("test-token"))
			},
			wantAuthPrefix: "Bearer ",
		},
		{
			name: "BasicAuth",
			newClient: func(baseURL string) *AggregatorClient {
				return NewBasicAuthClient(baseURL, "dbaas-operator", "password")
			},
			wantAuthPrefix: "Basic ",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var gotRequestID, gotAuthorization string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotRequestID = r.Header.Get(xrequestid.X_REQUEST_ID_HEADER_NAME)
				gotAuthorization = r.Header.Get("Authorization")
				w.WriteHeader(http.StatusOK)
			}))
			defer srv.Close()

			c := tt.newClient(srv.URL)
			err := c.RegisterExternalDatabase(contextWithRequestID(requestID), "ns", minimalExtDBRequest())
			if err != nil {
				t.Fatalf("RegisterExternalDatabase: %v", err)
			}
			if gotRequestID != requestID {
				t.Errorf("%s header: got %q, want %q",
					xrequestid.X_REQUEST_ID_HEADER_NAME, gotRequestID, requestID)
			}
			if !strings.HasPrefix(gotAuthorization, tt.wantAuthPrefix) {
				t.Errorf("Authorization: got %q, want prefix %q", gotAuthorization, tt.wantAuthPrefix)
			}
		})
	}
}

func TestAggregatorClient_RejectsMissingRequestID(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Error("request without X-Request-Id reached the server")
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())
	if err == nil {
		t.Fatal("expected missing request ID to reject the request")
	}
	var requestContextErr *RequestContextError
	if !errors.As(err, &requestContextErr) {
		t.Fatalf("error type = %T, want *RequestContextError", err)
	}
	if !strings.Contains(err.Error(), "request ID context is not initialized") ||
		!strings.Contains(err.Error(), "call WithFreshRequestID") {
		t.Errorf("unexpected error: %v", err)
	}
}

// ── RegisterExternalDatabase ──────────────────────────────────────────────────

func TestRegisterExternalDatabase_UsesCorrectURLAndMethod(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if err := c.RegisterExternalDatabase(requestContext(), "my-namespace", minimalExtDBRequest()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if gotMethod != http.MethodPut {
		t.Errorf("method: got %q, want PUT", gotMethod)
	}
	want := "/api/v3/dbaas/my-namespace/databases/registration/externally_manageable"
	if gotPath != want {
		t.Errorf("path: got %q, want %q", gotPath, want)
	}
}

func TestRegisterExternalDatabase_SendsBearerToken(t *testing.T) {
	t.Parallel()

	var gotToken string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotToken = bearerToken(r)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("my-dbaas-token"))
	_ = c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())

	if gotToken != "my-dbaas-token" {
		t.Errorf("Authorization: got Bearer %q, want Bearer my-dbaas-token", gotToken)
	}
}

// TestRegisterExternalDatabase_TokenFetchedPerRequest verifies that the token
// function is called on every request, not cached by the client.
func TestRegisterExternalDatabase_TokenFetchedPerRequest(t *testing.T) {
	t.Parallel()

	var tokens []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tokens = append(tokens, bearerToken(r))
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	call := 0
	tokenFn := func(context.Context) (string, error) {
		call++
		if call == 1 {
			return "token-first", nil
		}
		return "token-second", nil
	}

	c := newClient(srv.URL, tokenFn)
	_ = c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())
	_ = c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())

	if len(tokens) != 2 {
		t.Fatalf("expected 2 requests, got %d", len(tokens))
	}
	if tokens[0] != "token-first" {
		t.Errorf("first request: got %q, want token-first", tokens[0])
	}
	if tokens[1] != "token-second" {
		t.Errorf("second request: got %q, want token-second", tokens[1])
	}
}

// ── Basic Auth mode (KUBERNETES_M2M_ENABLED=false) ────────────────────────────

// TestBasicAuthClient_SendsBasicAuth verifies that a client built with
// NewBasicAuthClient authenticates with HTTP Basic Auth (not a Bearer token).
func TestBasicAuthClient_SendsBasicAuth(t *testing.T) {
	t.Parallel()

	var gotUser, gotPass string
	var gotOK bool
	var gotAuthHeader string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUser, gotPass, gotOK = r.BasicAuth()
		gotAuthHeader = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := NewBasicAuthClient(srv.URL, "dbaas-operator", "s3cr3t")
	_ = c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())

	if !gotOK || gotUser != "dbaas-operator" || gotPass != "s3cr3t" {
		t.Errorf("BasicAuth: got (%q,%q,ok=%v), want (dbaas-operator,s3cr3t,ok=true)", gotUser, gotPass, gotOK)
	}
	if strings.HasPrefix(gotAuthHeader, "Bearer ") {
		t.Errorf("expected Basic auth, got Bearer header %q", gotAuthHeader)
	}
}

// TestBasicAuthClient_SetCredentialsHotSwap verifies that SetCredentials replaces
// the credentials used for subsequent requests (Secret rotation without restart).
func TestBasicAuthClient_SetCredentialsHotSwap(t *testing.T) {
	t.Parallel()

	var passwords []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, pass, _ := r.BasicAuth()
		passwords = append(passwords, pass)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := NewBasicAuthClient(srv.URL, "dbaas-operator", "old-pass")
	_ = c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())
	c.SetCredentials("dbaas-operator", "new-pass")
	_ = c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())

	if len(passwords) != 2 {
		t.Fatalf("expected 2 requests, got %d", len(passwords))
	}
	if passwords[0] != "old-pass" {
		t.Errorf("first request: got %q, want old-pass", passwords[0])
	}
	if passwords[1] != "new-pass" {
		t.Errorf("second request after SetCredentials: got %q, want new-pass", passwords[1])
	}
}

func TestRegisterExternalDatabase_SerializesRequestBody(t *testing.T) {
	t.Parallel()

	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body = make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	req := &ExternalDatabaseRequest{
		Classifier:                 map[string]any{"namespace": "ns", "microserviceName": "svc"},
		Type:                       dbTypePostgresql,
		DBName:                     "mydb",
		ConnectionProperties:       []map[string]string{{"role": "admin", "host": "pg:5432"}},
		UpdateConnectionProperties: true,
	}
	c := newClient(srv.URL, staticToken("test-token"))
	if err := c.RegisterExternalDatabase(requestContext(), "ns", req); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var got ExternalDatabaseRequest
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("unmarshal body: %v", err)
	}
	if got.Type != dbTypePostgresql || got.DBName != "mydb" || !got.UpdateConnectionProperties {
		t.Errorf("body mismatch: %+v", got)
	}
}

func TestRegisterExternalDatabase_HTTP200IsSuccess(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if err := c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest()); err != nil {
		t.Errorf("HTTP 200 should be success, got: %v", err)
	}
}

func TestRegisterExternalDatabase_HTTP201IsSuccess(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if err := c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest()); err != nil {
		t.Errorf("HTTP 201 should be success, got: %v", err)
	}
}

func TestRegisterExternalDatabase_NonSuccessReturnsAggregatorError(t *testing.T) {
	t.Parallel()

	cases := []int{
		http.StatusBadRequest,
		http.StatusUnauthorized,
		http.StatusForbidden,
		http.StatusConflict,
		http.StatusInternalServerError,
	}

	for _, code := range cases {
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := newClient(srv.URL, staticToken("test-token"))
			err := c.RegisterExternalDatabase(requestContext(), "ns", minimalExtDBRequest())
			if err == nil {
				t.Fatalf("expected error for HTTP %d, got nil", code)
			}
			aggErr, ok := err.(*AggregatorError)
			if !ok {
				t.Fatalf("expected *AggregatorError, got %T: %v", err, err)
			}
			if aggErr.StatusCode != code {
				t.Errorf("StatusCode: got %d, want %d", aggErr.StatusCode, code)
			}
		})
	}
}

func TestRegisterExternalDatabase_ContextCancellation(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	ctx, cancel := context.WithCancel(requestContext())
	cancel()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.RegisterExternalDatabase(ctx, "ns", minimalExtDBRequest())
	if err == nil {
		t.Error("expected error on canceled context, got nil")
	}
}

func TestApplyMicroserviceBalancingRules_UsesCorrectURLMethodAndBody(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	var got []OnMicroserviceRuleRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode body: %v", err)
		}
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.ApplyMicroserviceBalancingRules(requestContext(), "payments", []OnMicroserviceRuleRequest{{
		Type:          dbTypePostgresql,
		Rules:         []RuleOnMicroservice{{Label: "zone=fast"}},
		Microservices: []string{"billing-service"},
	}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodPut {
		t.Errorf("method: got %q, want PUT", gotMethod)
	}
	wantPath := "/api/v3/dbaas/payments/physical_databases/rules/onMicroservices"
	if gotPath != wantPath {
		t.Errorf("path: got %q, want %q", gotPath, wantPath)
	}
	if len(got) != 1 || got[0].Rules[0].Label != "zone=fast" {
		t.Fatalf("body mismatch: %+v", got)
	}
}

func TestApplyNamespaceBalancingRule_UsesCorrectURLMethodAndBody(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	var got NamespaceBalancingRuleRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	order := int64(3)
	c := newClient(srv.URL, staticToken("test-token"))
	err := c.ApplyNamespaceBalancingRule(requestContext(), "payments", "pg-fast", &NamespaceBalancingRuleRequest{
		Order: &order,
		Type:  dbTypePostgresql,
		Rule: NamespaceBalancingRuleBody{
			Type: "perNamespace",
			Config: map[string]any{
				"perNamespace": map[string]any{"phydbid": "postgresql-sample"},
			},
		},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodPut {
		t.Errorf("method: got %q, want PUT", gotMethod)
	}
	wantPath := "/api/v3/dbaas/payments/physical_databases/balancing/rules/pg-fast"
	if gotPath != wantPath {
		t.Errorf("path: got %q, want %q", gotPath, wantPath)
	}
	if got.Rule.Type != "perNamespace" || got.Rule.Config["perNamespace"] == nil {
		t.Fatalf("body mismatch: %+v", got)
	}
}

func TestDeleteNamespaceBalancingRule_UsesCorrectURLAndMethod(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if err := c.DeleteNamespaceBalancingRule(requestContext(), "payments", "pg-fast"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodDelete {
		t.Errorf("method: got %q, want DELETE", gotMethod)
	}
	wantPath := "/api/v3/dbaas/payments/physical_databases/balancing/rules/pg-fast"
	if gotPath != wantPath {
		t.Errorf("path: got %q, want %q", gotPath, wantPath)
	}
}

func TestDeleteNamespaceBalancingRule_SuccessStatuses(t *testing.T) {
	t.Parallel()

	for _, code := range []int{http.StatusOK, http.StatusNoContent, http.StatusNotFound} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := newClient(srv.URL, staticToken("test-token"))
			if err := c.DeleteNamespaceBalancingRule(requestContext(), "payments", "pg-fast"); err != nil {
				t.Errorf("HTTP %d should be success, got: %v", code, err)
			}
		})
	}
}

func TestDeleteNamespaceBalancingRule_NonSuccessReturnsError(t *testing.T) {
	t.Parallel()

	for _, code := range []int{http.StatusMethodNotAllowed, http.StatusInternalServerError} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := newClient(srv.URL, staticToken("test-token"))
			if err := c.DeleteNamespaceBalancingRule(requestContext(), "payments", "pg-fast"); err == nil {
				t.Fatalf("expected error for HTTP %d, got nil", code)
			}
		})
	}
}

func TestDeleteNamespaceBalancingRule_ContextCancellation(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithCancel(requestContext())
	cancel()

	c := newClient("http://127.0.0.1", staticToken("test-token"))
	if err := c.DeleteNamespaceBalancingRule(ctx, "payments", "pg-fast"); err == nil {
		t.Fatal("expected error on canceled context, got nil")
	}
}

func TestApplyPermanentBalancingRules_UsesCorrectURLMethodAndBody(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	var got []PermanentBalancingRuleRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.ApplyPermanentBalancingRules(requestContext(), []PermanentBalancingRuleRequest{{
		DBType:             dbTypePostgresql,
		PhysicalDatabaseID: "postgresql-prod-a",
		Namespaces:         []string{"payments", "orders"},
	}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodPut {
		t.Errorf("method: got %q, want PUT", gotMethod)
	}
	wantPath := "/api/v3/dbaas/balancing/rules/permanent"
	if gotPath != wantPath {
		t.Errorf("path: got %q, want %q", gotPath, wantPath)
	}
	if len(got) != 1 || got[0].PhysicalDatabaseID != "postgresql-prod-a" {
		t.Fatalf("body mismatch: %+v", got)
	}
}

func TestDeletePermanentBalancingRules_UsesCorrectURLMethodAndBody(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	var got []PermanentBalancingRuleDeleteRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Fatalf("decode body: %v", err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.DeletePermanentBalancingRules(requestContext(), []PermanentBalancingRuleDeleteRequest{{
		DBType:     dbTypePostgresql,
		Namespaces: []string{"payments"},
	}})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodDelete {
		t.Errorf("method: got %q, want DELETE", gotMethod)
	}
	wantPath := "/api/v3/dbaas/balancing/rules/permanent"
	if gotPath != wantPath {
		t.Errorf("path: got %q, want %q", gotPath, wantPath)
	}
	if len(got) != 1 || got[0].DBType != dbTypePostgresql || got[0].Namespaces[0] != "payments" {
		t.Fatalf("body mismatch: %+v", got)
	}
}

// ── ApplyConfig ───────────────────────────────────────────────────────────────

func TestApplyConfig_UsesCorrectURLAndMethod(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		writeJSON(t, w, http.StatusOK, DeclarativeResponse{Status: TaskStateCompleted})
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if _, err := c.ApplyConfig(requestContext(), minimalDeclarativePayload()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if gotMethod != http.MethodPost {
		t.Errorf("method: got %q, want POST", gotMethod)
	}
	if gotPath != "/api/declarations/v1/apply" {
		t.Errorf("path: got %q, want /api/declarations/v1/apply", gotPath)
	}
}

func TestApplyConfig_HTTP200SyncResponse(t *testing.T) {
	t.Parallel()

	resp := DeclarativeResponse{
		Status: TaskStateCompleted,
		Conditions: []AggregatorCondition{
			{Type: "DataBaseCreated", State: "COMPLETED"},
		},
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, http.StatusOK, resp)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	got, err := c.ApplyConfig(requestContext(), minimalDeclarativePayload())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.TrackingID != "" {
		t.Errorf("sync response should have empty TrackingID, got %q", got.TrackingID)
	}
	if got.Status != TaskStateCompleted {
		t.Errorf("status: got %q, want COMPLETED", got.Status)
	}
	if len(got.Conditions) != 1 || got.Conditions[0].Type != "DataBaseCreated" {
		t.Errorf("conditions mismatch: %+v", got.Conditions)
	}
}

func TestApplyConfig_HTTP202AsyncResponse(t *testing.T) {
	t.Parallel()

	resp := DeclarativeResponse{
		Status:     TaskStateInProgress,
		TrackingID: "abc-123",
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, http.StatusAccepted, resp)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	got, err := c.ApplyConfig(requestContext(), minimalDeclarativePayload())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.TrackingID != "abc-123" {
		t.Errorf("TrackingID: got %q, want abc-123", got.TrackingID)
	}
	if got.Status != TaskStateInProgress {
		t.Errorf("status: got %q, want IN_PROGRESS", got.Status)
	}
}

func TestApplyConfig_NonSuccessReturnsAggregatorError(t *testing.T) {
	t.Parallel()

	cases := []int{
		http.StatusBadRequest,
		http.StatusUnauthorized,
		http.StatusInternalServerError,
	}

	for _, code := range cases {
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := newClient(srv.URL, staticToken("test-token"))
			_, err := c.ApplyConfig(requestContext(), minimalDeclarativePayload())
			if err == nil {
				t.Fatalf("expected error for HTTP %d, got nil", code)
			}
			aggErr, ok := err.(*AggregatorError)
			if !ok {
				t.Fatalf("expected *AggregatorError, got %T", err)
			}
			if aggErr.StatusCode != code {
				t.Errorf("StatusCode: got %d, want %d", aggErr.StatusCode, code)
			}
		})
	}
}

func TestApplyConfig_SerializesDeclarationVersion(t *testing.T) {
	t.Parallel()

	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body = make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		writeJSON(t, w, http.StatusOK, DeclarativeResponse{Status: TaskStateCompleted})
	}))
	defer srv.Close()

	payload := minimalDeclarativePayload()
	payload.DeclarationVersion = "v2"

	c := newClient(srv.URL, staticToken("test-token"))
	if _, err := c.ApplyConfig(requestContext(), payload); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !strings.Contains(string(body), `"declarationVersion":"v2"`) {
		t.Errorf("declarationVersion not serialized into body: %s", body)
	}
}

func TestApplyConfig_OmitsDeclarationVersionWhenEmpty(t *testing.T) {
	t.Parallel()

	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body = make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		writeJSON(t, w, http.StatusOK, DeclarativeResponse{Status: TaskStateCompleted})
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if _, err := c.ApplyConfig(requestContext(), minimalDeclarativePayload()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if strings.Contains(string(body), "declarationVersion") {
		t.Errorf("declarationVersion should be omitted when empty, body: %s", body)
	}
}

// ── GetOperationStatus ────────────────────────────────────────────────────────

func TestGetOperationStatus_UsesCorrectURLAndMethod(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		writeJSON(t, w, http.StatusOK, DeclarativeResponse{Status: TaskStateCompleted})
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	if _, err := c.GetOperationStatus(requestContext(), "track-99"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if gotMethod != http.MethodGet {
		t.Errorf("method: got %q, want GET", gotMethod)
	}
	want := "/api/declarations/v1/operation/track-99/status"
	if gotPath != want {
		t.Errorf("path: got %q, want %q", gotPath, want)
	}
}

func TestGetOperationStatus_InProgressResponse(t *testing.T) {
	t.Parallel()

	resp := DeclarativeResponse{
		Status:     TaskStateInProgress,
		TrackingID: "track-99",
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, http.StatusOK, resp)
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	got, err := c.GetOperationStatus(requestContext(), "track-99")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Status != TaskStateInProgress {
		t.Errorf("status: got %q, want IN_PROGRESS", got.Status)
	}
}

func TestGetOperationStatus_AllTerminalStates(t *testing.T) {
	t.Parallel()

	cases := []TaskState{
		TaskStateCompleted,
		TaskStateFailed,
		TaskStateTerminated,
		TaskStateNotStarted,
	}

	for _, state := range cases {
		t.Run(string(state), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				writeJSON(t, w, http.StatusOK, DeclarativeResponse{Status: state})
			}))
			defer srv.Close()

			c := newClient(srv.URL, staticToken("test-token"))
			got, err := c.GetOperationStatus(requestContext(), "tid")
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got.Status != state {
				t.Errorf("status: got %q, want %q", got.Status, state)
			}
		})
	}
}

func TestGetOperationStatus_NonSuccessReturnsAggregatorError(t *testing.T) {
	t.Parallel()

	cases := []int{
		http.StatusBadRequest,
		http.StatusNotFound,
		http.StatusInternalServerError,
	}

	for _, code := range cases {
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := newClient(srv.URL, staticToken("test-token"))
			_, err := c.GetOperationStatus(requestContext(), "tid")
			if err == nil {
				t.Fatalf("expected error for HTTP %d, got nil", code)
			}
			aggErr, ok := err.(*AggregatorError)
			if !ok {
				t.Fatalf("expected *AggregatorError, got %T", err)
			}
			if aggErr.StatusCode != code {
				t.Errorf("StatusCode: got %d, want %d", aggErr.StatusCode, code)
			}
		})
	}
}

func TestGetOperationStatus_ParsesTmfMessage(t *testing.T) {
	t.Parallel()

	const tmfMessage = "Operation trackingId not found."
	tmfBody := `{"code":"CORE-DBAAS-4040","reason":"TrackingID not found","message":"` + tmfMessage + `","status":"404","@type":"NC.TMFErrorResponse.v1.0"}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(tmfBody))
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	_, err := c.GetOperationStatus(requestContext(), "tid")
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	var aggErr *AggregatorError
	if !errors.As(err, &aggErr) {
		t.Fatalf("expected *AggregatorError, got %T", err)
	}
	if aggErr.StatusCode != http.StatusNotFound {
		t.Errorf("StatusCode: got %d, want 404", aggErr.StatusCode)
	}
	if aggErr.TmfMessage != tmfMessage {
		t.Errorf("TmfMessage: got %q, want %q", aggErr.TmfMessage, tmfMessage)
	}
	if aggErr.UserMessage() != tmfMessage {
		t.Errorf("UserMessage(): got %q, want %q", aggErr.UserMessage(), tmfMessage)
	}
}

// ── AggregatorError ───────────────────────────────────────────────────────────

func TestAggregatorError_IsAuthError(t *testing.T) {
	t.Parallel()

	cases := []struct {
		code int
		want bool
	}{
		{http.StatusUnauthorized, true},
		{http.StatusBadRequest, false},
		{http.StatusForbidden, false},
		{http.StatusConflict, false},
		{http.StatusInternalServerError, false},
	}

	for _, tc := range cases {
		t.Run(http.StatusText(tc.code), func(t *testing.T) {
			t.Parallel()
			e := &AggregatorError{StatusCode: tc.code}
			if got := e.IsAuthError(); got != tc.want {
				t.Errorf("IsAuthError() for %d: got %v, want %v", tc.code, got, tc.want)
			}
		})
	}
}

func TestAggregatorError_IsSpecRejection(t *testing.T) {
	t.Parallel()

	cases := []struct {
		code int
		want bool
	}{
		{http.StatusBadRequest, true},
		{http.StatusForbidden, true},
		{http.StatusConflict, true},
		{http.StatusGone, true},
		{http.StatusUnprocessableEntity, true},
		{http.StatusUnauthorized, false},
		{http.StatusNotFound, false},
		{http.StatusMethodNotAllowed, false},
		{http.StatusRequestTimeout, false},
		{http.StatusTooManyRequests, false},
		{http.StatusInternalServerError, false},
		{http.StatusBadGateway, false},
		{http.StatusServiceUnavailable, false},
	}

	for _, tc := range cases {
		t.Run(http.StatusText(tc.code), func(t *testing.T) {
			t.Parallel()
			e := &AggregatorError{StatusCode: tc.code}
			if got := e.IsSpecRejection(); got != tc.want {
				t.Errorf("IsSpecRejection() for %d: got %v, want %v", tc.code, got, tc.want)
			}
		})
	}
}

func TestAggregatorError_ErrorMessage(t *testing.T) {
	t.Parallel()
	e := &AggregatorError{StatusCode: 409, Body: "conflict"}
	msg := e.Error()
	if !strings.Contains(msg, "409") || !strings.Contains(msg, "conflict") {
		t.Errorf("Error() = %q, want it to contain status code and body", msg)
	}
}

// ── TMF error parsing ─────────────────────────────────────────────────────────

func TestRegisterExternalDatabase_ParsesTmfMessage(t *testing.T) {
	t.Parallel()

	const tmfMessage = "Invalid classifier. Field 'type' has invalid value."
	tmfBody := `{"code":"CORE-DBAAS-4010","reason":"Invalid classifier","message":"` + tmfMessage + `","status":"400","@type":"NC.TMFErrorResponse.v1.0"}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(tmfBody))
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.RegisterExternalDatabase(requestContext(), "test", minimalExtDBRequest())
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	var aggErr *AggregatorError
	if !errors.As(err, &aggErr) {
		t.Fatalf("expected *AggregatorError, got %T", err)
	}
	if aggErr.StatusCode != http.StatusBadRequest {
		t.Errorf("StatusCode: got %d, want 400", aggErr.StatusCode)
	}
	if aggErr.TmfMessage != tmfMessage {
		t.Errorf("TmfMessage: got %q, want %q", aggErr.TmfMessage, tmfMessage)
	}
	if aggErr.UserMessage() != tmfMessage {
		t.Errorf("UserMessage(): got %q, want %q", aggErr.UserMessage(), tmfMessage)
	}
	if !aggErr.IsSpecRejection() {
		t.Error("IsSpecRejection() should be true for HTTP 400")
	}
}

func TestRegisterExternalDatabase_NonTmfBodyFallback(t *testing.T) {
	t.Parallel()

	const rawBody = "internal server error"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(rawBody))
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.RegisterExternalDatabase(requestContext(), "test", minimalExtDBRequest())
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	var aggErr *AggregatorError
	if !errors.As(err, &aggErr) {
		t.Fatalf("expected *AggregatorError, got %T", err)
	}
	if aggErr.TmfMessage != "" {
		t.Errorf("TmfMessage: got %q, want empty string", aggErr.TmfMessage)
	}
	if aggErr.UserMessage() != rawBody {
		t.Errorf("UserMessage(): got %q, want %q", aggErr.UserMessage(), rawBody)
	}
}

func TestRegisterExternalDatabase_TmfEmptyMessageFallback(t *testing.T) {
	t.Parallel()

	const tmfBody = `{"code":"CORE-DBAAS-4002","reason":"Conflict database request","status":"409","@type":"NC.TMFErrorResponse.v1.0"}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(tmfBody))
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.RegisterExternalDatabase(requestContext(), "test", minimalExtDBRequest())
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	var aggErr *AggregatorError
	if !errors.As(err, &aggErr) {
		t.Fatalf("expected *AggregatorError, got %T", err)
	}
	if aggErr.TmfMessage != "" {
		t.Errorf("TmfMessage: got %q, want empty (no message field in TMF body)", aggErr.TmfMessage)
	}
	if aggErr.UserMessage() != tmfBody {
		t.Errorf("UserMessage(): got %q, want raw TMF body", aggErr.UserMessage())
	}
}

// ── empty-body handling (decodeInto allowEmpty) ───────────────────────────────

// A 200 with an empty body on the changed-databases feed must be a decode error,
// NOT a zero ChangedDatabasesResponse. A zero value (HighWaterMark=nil) would read
// as "no rotation history" and seed the poller cursor at epoch, triggering a full
// rotation replay instead of a safe retry on the next tick.
func TestGetChangedSince_EmptyBodyReturnsError(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK) // 200 with no body
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	got, err := c.GetChangedSince(requestContext(), nil, 0)
	if err == nil {
		t.Fatalf("expected an error for an empty 200 body, got nil (result=%+v)", got)
	}
	if got != nil {
		t.Errorf("result must be nil on error, got %+v", got)
	}
}

// A 200 with an empty body on get-by-classifier must be a decode error, not an
// empty DatabaseResponseSingleCP.
func TestGetDatabaseByClassifier_EmptyBodyReturnsError(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK) // 200 with no body
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	got, err := c.GetDatabaseByClassifier(requestContext(), "test-ns", dbTypePostgresql,
		&GetByClassifierRequest{Classifier: map[string]any{"namespace": "test-ns"}})
	if err == nil {
		t.Fatalf("expected an error for an empty 200 body, got nil (result=%+v)", got)
	}
	if got != nil {
		t.Errorf("result must be nil on error, got %+v", got)
	}
}

// The declarative apply and operation-status endpoints tolerate an empty body: a
// success status with no payload yields the zero value, not an error.
func TestGetOperationStatus_EmptyBodyIsZeroValue(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK) // 200 with no body
	}))
	defer srv.Close()

	c := newClient(srv.URL, staticToken("test-token"))
	got, err := c.GetOperationStatus(requestContext(), "tracking-123")
	if err != nil {
		t.Fatalf("empty declarative body must not error, got %v", err)
	}
	if got == nil {
		t.Fatalf("expected a zero-value response, got nil")
	}
	if got.Status != "" || got.TrackingID != "" || len(got.Conditions) != 0 {
		t.Errorf("expected zero-value DeclarativeResponse, got %+v", got)
	}
}

// TestCreateDatabase_StatusHandling covers the three outcomes of the tenant-materialization call.
// A 202 means the aggregator started creating the database asynchronously and its body carries no
// usable credentials yet, so it must be reported as pending rather than as success or as an error —
// treating it as an error left tenant InternalDatabases stuck in BackingOff and their
// DatabaseSecretClaims without a Secret.
func TestCreateDatabase_StatusHandling(t *testing.T) {
	tests := []struct {
		name        string
		status      int
		body        string
		wantPending bool
		wantErr     bool
	}{
		{name: "ready", status: http.StatusOK, body: `{"name":"db"}`},
		{name: "created", status: http.StatusCreated, body: `{"name":"db"}`},
		{
			name:        "accepted, still provisioning",
			status:      http.StatusAccepted,
			body:        `{"classifier":{"scope":"tenant","tenantId":"acme"},"connectionProperties":{"password":null,"role":"admin"}}`,
			wantPending: true,
		},
		{name: "rejected", status: http.StatusBadRequest, body: `{"message":"bad classifier"}`, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPut {
					t.Errorf("method = %s, want PUT", r.Method)
				}
				w.WriteHeader(tt.status)
				_, _ = w.Write([]byte(tt.body))
			}))
			defer srv.Close()

			pending, err := NewBasicAuthClient(srv.URL, "dbaas-operator", "s3cr3t").CreateDatabase(
				requestContext(), "ns", &CreateDatabaseRequest{Type: "postgresql"})

			if tt.wantErr {
				if err == nil {
					t.Fatal("expected an error, got nil")
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if pending != tt.wantPending {
				t.Errorf("pending = %v, want %v", pending, tt.wantPending)
			}
		})
	}
}
