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
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
)

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

// minimalExtDBRequest returns the smallest valid ExternalDatabaseRequest.
func minimalExtDBRequest() *ExternalDatabaseRequest {
	return &ExternalDatabaseRequest{
		Classifier:           map[string]string{"namespace": "test"},
		Type:                 "postgresql",
		DbName:               "test-db",
		ConnectionProperties: []map[string]string{{"role": "admin"}},
	}
}
