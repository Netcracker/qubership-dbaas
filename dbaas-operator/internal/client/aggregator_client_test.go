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
	"sync"
	"testing"
)

// ── helpers ───────────────────────────────────────────────────────────────────

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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "my-sa-token")
	_ = c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())

	if gotToken != "my-sa-token" {
		t.Errorf("Authorization: got Bearer %q, want Bearer my-sa-token", gotToken)
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
	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

			c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

			c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

			c := NewAggregatorClient(srv.URL, "test-token")
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

			c := NewAggregatorClient(srv.URL, "test-token")
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

	c := NewAggregatorClient(srv.URL, "test-token")
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
		// Permanent spec rejections — aggregator explicitly rejects the payload.
		{http.StatusBadRequest, true},          // 400 — validation failure
		{http.StatusForbidden, true},           // 403 — namespace/policy violation
		{http.StatusConflict, true},            // 409 — resource already exists
		{http.StatusGone, true},                // 410 — resource permanently removed
		{http.StatusUnprocessableEntity, true}, // 422 — semantic validation failure
		// Infrastructure / proxy 4xx — transient, must NOT be spec rejections.
		{http.StatusUnauthorized, false},     // 401 — handled by IsAuthError
		{http.StatusNotFound, false},         // 404 — routing/proxy issue
		{http.StatusMethodNotAllowed, false}, // 405 — wrong HTTP method
		{http.StatusRequestTimeout, false},   // 408 — transient timeout
		{http.StatusTooManyRequests, false},  // 429 — rate limit
		// Server errors — transient.
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

// ── SetToken ──────────────────────────────────────────────────────────────────

// TestSetToken_ChangesAuthHeader verifies that SetToken causes subsequent
// requests to carry the updated Bearer token.
func TestSetToken_ChangesAuthHeader(t *testing.T) {
	t.Parallel()

	var mu sync.Mutex
	var capturedToken string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := bearerToken(r)
		mu.Lock()
		capturedToken = tok
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	client := NewAggregatorClient(srv.URL, "token-v1")
	req := minimalExtDBRequest()

	// first request: original token
	if err := client.RegisterExternalDatabase(context.Background(), "test", req); err != nil {
		t.Fatalf("first request: %v", err)
	}
	mu.Lock()
	t1 := capturedToken
	mu.Unlock()
	if t1 != "token-v1" {
		t.Errorf("before SetToken: got %q, want token-v1", t1)
	}

	// second request: updated token
	client.SetToken("token-v2")

	if err := client.RegisterExternalDatabase(context.Background(), "test", req); err != nil {
		t.Fatalf("second request: %v", err)
	}
	mu.Lock()
	t2 := capturedToken
	mu.Unlock()
	if t2 != "token-v2" {
		t.Errorf("after SetToken: got %q, want token-v2", t2)
	}
}

// TestSetToken_Concurrent runs concurrent reads (HTTP requests) and
// writes (SetToken) to detect data races. Run with -race flag.
func TestSetToken_Concurrent(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	client := NewAggregatorClient(srv.URL, "initial-token")
	req := minimalExtDBRequest()

	const readers = 8
	const iterations = 50

	var wg sync.WaitGroup

	// concurrent readers
	for range readers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range iterations {
				_ = client.RegisterExternalDatabase(context.Background(), "test", req)
			}
		}()
	}

	// concurrent writer
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := range readers * iterations {
			if i%2 == 0 {
				client.SetToken("token-a")
			} else {
				client.SetToken("token-b")
			}
		}
	}()

	wg.Wait()
}

// ── TMF error parsing ─────────────────────────────────────────────────────────

// TestRegisterExternalDatabase_ParsesTmfMessage verifies that when dbaas-aggregator
// returns a TmfErrorResponse JSON body, the client populates AggregatorError.TmfMessage
// and UserMessage() returns the TMF message rather than the raw body.
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

	c := NewAggregatorClient(srv.URL, "test-token")
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

// TestRegisterExternalDatabase_NonTmfBodyFallback verifies that when the response
// body is not valid TMF JSON, UserMessage() falls back to the raw body.
func TestRegisterExternalDatabase_NonTmfBodyFallback(t *testing.T) {
	t.Parallel()

	const rawBody = "internal server error"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(rawBody))
	}))
	defer srv.Close()

	c := NewAggregatorClient(srv.URL, "test-token")
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

// TestRegisterExternalDatabase_TmfEmptyMessageFallback verifies that a valid TMF JSON
// body with an empty "message" field falls back to the raw body in UserMessage().
func TestRegisterExternalDatabase_TmfEmptyMessageFallback(t *testing.T) {
	t.Parallel()

	// Valid TMF JSON but message field is absent/empty.
	const tmfBody = `{"code":"CORE-DBAAS-4002","reason":"Conflict database request","status":"409","@type":"NC.TMFErrorResponse.v1.0"}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(tmfBody))
	}))
	defer srv.Close()

	c := NewAggregatorClient(srv.URL, "test-token")
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
	// UserMessage() must fall back to the raw body.
	if aggErr.UserMessage() != tmfBody {
		t.Errorf("UserMessage(): got %q, want raw TMF body", aggErr.UserMessage())
	}
}
