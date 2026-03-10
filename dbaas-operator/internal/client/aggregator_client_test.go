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

// minimalExtDBRequest returns the smallest valid ExternalDatabaseRequest.
func minimalExtDBRequest() *ExternalDatabaseRequest {
	return &ExternalDatabaseRequest{
		Classifier:           map[string]string{"namespace": "test"},
		Type:                 "postgresql",
		DbName:               "test-db",
		ConnectionProperties: []map[string]string{{"role": "admin"}},
	}
}
