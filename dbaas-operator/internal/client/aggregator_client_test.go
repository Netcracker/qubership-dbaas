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

	c := NewAggregatorClient(srv.URL, "u", "p")
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

func TestRegisterExternalDatabase_SendsBasicAuth(t *testing.T) {
	t.Parallel()

	var gotUser, gotPass string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUser, gotPass, _ = r.BasicAuth()
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := NewAggregatorClient(srv.URL, "alice", "secret")
	_ = c.RegisterExternalDatabase(context.Background(), "ns", minimalExtDBRequest())

	if gotUser != "alice" || gotPass != "secret" {
		t.Errorf("basic auth: got %q/%q, want alice/secret", gotUser, gotPass)
	}
}

func TestRegisterExternalDatabase_SerializesRequestBody(t *testing.T) {
	t.Parallel()

	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var err error
		body = make([]byte, r.ContentLength)
		_, err = r.Body.Read(body)
		_ = err
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
	c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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
		code := code
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
	got, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.TrackingId != "" {
		t.Errorf("sync response should have empty TrackingId, got %q", got.TrackingId)
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
		TrackingId: "abc-123",
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, http.StatusAccepted, resp)
	}))
	defer srv.Close()

	c := NewAggregatorClient(srv.URL, "u", "p")
	got, err := c.ApplyConfig(context.Background(), minimalDeclarativePayload())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.TrackingId != "abc-123" {
		t.Errorf("TrackingId: got %q, want abc-123", got.TrackingId)
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
		code := code
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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

	c := NewAggregatorClient(srv.URL, "u", "p")
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
		TrackingId: "track-99",
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, http.StatusOK, resp)
	}))
	defer srv.Close()

	c := NewAggregatorClient(srv.URL, "u", "p")
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
		state := state
		t.Run(string(state), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				writeJSON(t, w, http.StatusOK, DeclarativeResponse{Status: state})
			}))
			defer srv.Close()

			c := NewAggregatorClient(srv.URL, "u", "p")
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
		code := code
		t.Run(http.StatusText(code), func(t *testing.T) {
			t.Parallel()
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(code)
			}))
			defer srv.Close()

			c := NewAggregatorClient(srv.URL, "u", "p")
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
		tc := tc
		t.Run(http.StatusText(tc.code), func(t *testing.T) {
			t.Parallel()
			e := &AggregatorError{StatusCode: tc.code}
			if got := e.IsAuthError(); got != tc.want {
				t.Errorf("IsAuthError() for %d: got %v, want %v", tc.code, got, tc.want)
			}
		})
	}
}

func TestAggregatorError_IsClientError(t *testing.T) {
	t.Parallel()

	cases := []struct {
		code int
		want bool
	}{
		{http.StatusBadRequest, true},
		{http.StatusUnauthorized, true},
		{http.StatusForbidden, true},
		{http.StatusConflict, true},
		{http.StatusInternalServerError, false},
		{http.StatusBadGateway, false},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(http.StatusText(tc.code), func(t *testing.T) {
			t.Parallel()
			e := &AggregatorError{StatusCode: tc.code}
			if got := e.IsClientError(); got != tc.want {
				t.Errorf("IsClientError() for %d: got %v, want %v", tc.code, got, tc.want)
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

// ── SetCredentials ────────────────────────────────────────────────────────────

// TestSetCredentials_ChangesAuthHeader verifies that SetCredentials causes
// subsequent requests to carry the updated Basic Auth header.
func TestSetCredentials_ChangesAuthHeader(t *testing.T) {
	t.Parallel()

	var mu sync.Mutex
	var capturedUser, capturedPass string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u, p, _ := r.BasicAuth()
		mu.Lock()
		capturedUser, capturedPass = u, p
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	client := NewAggregatorClient(srv.URL, "user-v1", "pass-v1")
	req := minimalExtDBRequest()

	// ── first request: original credentials ──────────────────────────────────
	if err := client.RegisterExternalDatabase(context.Background(), "test", req); err != nil {
		t.Fatalf("first request: %v", err)
	}
	mu.Lock()
	u1, p1 := capturedUser, capturedPass
	mu.Unlock()
	if u1 != "user-v1" || p1 != "pass-v1" {
		t.Errorf("before SetCredentials: got %q/%q, want user-v1/pass-v1", u1, p1)
	}

	// ── second request: updated credentials ──────────────────────────────────
	client.SetCredentials("user-v2", "pass-v2")

	if err := client.RegisterExternalDatabase(context.Background(), "test", req); err != nil {
		t.Fatalf("second request: %v", err)
	}
	mu.Lock()
	u2, p2 := capturedUser, capturedPass
	mu.Unlock()
	if u2 != "user-v2" || p2 != "pass-v2" {
		t.Errorf("after SetCredentials: got %q/%q, want user-v2/pass-v2", u2, p2)
	}
}

// TestSetCredentials_Concurrent runs concurrent reads (HTTP requests) and
// writes (SetCredentials) to detect data races. Run with -race flag.
func TestSetCredentials_Concurrent(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	client := NewAggregatorClient(srv.URL, "user", "pass")
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
				client.SetCredentials("user-a", "pass-a")
			} else {
				client.SetCredentials("user-b", "pass-b")
			}
		}
	}()

	wg.Wait()
}

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

	c := NewAggregatorClient(srv.URL, "user", "pass")
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
	if !aggErr.IsClientError() {
		t.Error("IsClientError() should be true for HTTP 400")
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

	c := NewAggregatorClient(srv.URL, "user", "pass")
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

	c := NewAggregatorClient(srv.URL, "user", "pass")
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
