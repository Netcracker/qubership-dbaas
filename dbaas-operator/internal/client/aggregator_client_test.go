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
	"strings"
	"testing"
)

// ── helpers ───────────────────────────────────────────────────────────────────

// staticToken returns a TokenSource function that always returns the given token.
// Used in tests to avoid touching the global tokensource state.
func staticToken(token string) func(context.Context) (string, error) {
	return func(context.Context) (string, error) { return token, nil }
}

// minimalExtDBRequest returns the smallest valid ExternalDatabaseRequest.
func minimalExtDBRequest() *ExternalDatabaseRequest {
	return &ExternalDatabaseRequest{
		Classifier:           map[string]string{"namespace": "test"},
		Type:                 "postgresql",
		DbName:               "test-db",
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
		Spec: map[string]string{"type": "postgresql"},
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
	if err := c.RegisterExternalDatabase(context.Background(), "my-namespace", minimalExtDBRequest()); err != nil {
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
	_ = c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())

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
	_ = c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())
	_ = c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())

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
		Classifier:                 map[string]string{"namespace": "ns", "microserviceName": "svc"},
		Type:                       "postgresql",
		DbName:                     "mydb",
		ConnectionProperties:       []map[string]string{{"role": "admin", "host": "pg:5432"}},
		UpdateConnectionProperties: true,
	}
	c := newClient(srv.URL, staticToken("test-token"))
	if err := c.RegisterExternalDatabase(context.Background(), "ns", req); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var got ExternalDatabaseRequest
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("unmarshal body: %v", err)
	}
	if got.Type != "postgresql" || got.DbName != "mydb" || !got.UpdateConnectionProperties {
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
	if err := c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest()); err != nil {
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
	if err := c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest()); err != nil {
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
			err := c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())
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

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	c := newClient(srv.URL, staticToken("test-token"))
	err := c.RegisterExternalDatabase(ctx, "ns", minimalExtDBRequest())
	if err == nil {
		t.Error("expected error on cancelled context, got nil")
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
	if _, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload()); err != nil {
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
	got, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload())
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
	got, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload())
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
			_, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload())
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
	if _, err := c.ApplyConfig(context.Background(), payload); err != nil {
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
	if _, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload()); err != nil {
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
	if _, err := c.GetOperationStatus(context.Background(), "track-99"); err != nil {
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
	got, err := c.GetOperationStatus(context.Background(), "track-99")
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
			got, err := c.GetOperationStatus(context.Background(), "tid")
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
			_, err := c.GetOperationStatus(context.Background(), "tid")
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
	_, err := c.GetOperationStatus(context.Background(), "tid")
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
	err := c.RegisterExternalDatabase(context.Background(), "test", minimalExtDBRequest())
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
	err := c.RegisterExternalDatabase(context.Background(), "test", minimalExtDBRequest())
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
	err := c.RegisterExternalDatabase(context.Background(), "test", minimalExtDBRequest())
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
