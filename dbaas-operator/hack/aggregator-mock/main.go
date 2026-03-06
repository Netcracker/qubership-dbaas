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

// aggregator-mock is a minimal HTTP server that emulates the dbaas-aggregator
// endpoints used by dbaas-operator, for local development and kind-based testing.
//
// Environment variables:
//
//	PORT               – listen port (default: 8080)
//	MOCK_RESPONSE_CODE – HTTP status code returned for the PUT registration
//	                     endpoint (default: 200). Set to 400 to test
//	                     InvalidConfiguration; set to 500 to test BackingOff.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
)

var (
	// PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable
	reExternalDB = regexp.MustCompile(
		`^/api/v3/dbaas/([^/]+)/databases/registration/externally_manageable$`)

	// POST /api/declarations/v1/apply
	reApply = regexp.MustCompile(`^/api/declarations/v1/apply$`)

	// GET /api/declarations/v1/operation/{trackingId}/status
	reOpStatus = regexp.MustCompile(`^/api/declarations/v1/operation/([^/]+)/status$`)
)

func main() {
	port := envOr("PORT", "8080")
	responseCode := envInt("MOCK_RESPONSE_CODE", http.StatusOK)

	log.Printf("mock dbaas-aggregator starting on :%s", port)
	log.Printf("  PUT  .../externally_manageable → %d", responseCode)
	log.Printf("  POST .../apply                 → 200 (sync)")
	log.Printf("  GET  .../operation/.../status  → 200 COMPLETED")

	if err := http.ListenAndServe(":"+port, handler(responseCode)); err != nil {
		log.Fatalf("listen: %v", err)
	}
}

func handler(registrationCode int) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		logRequest(r)

		switch {
		case reExternalDB.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			handleExternalDB(w, r, registrationCode)

		case reApply.MatchString(r.URL.Path) && r.Method == http.MethodPost:
			handleApply(w, r)

		case reOpStatus.MatchString(r.URL.Path) && r.Method == http.MethodGet:
			handleOpStatus(w, r)

		default:
			log.Printf("  → 404 no route for %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})
	return mux
}

// handleExternalDB serves PUT .../externally_manageable.
func handleExternalDB(w http.ResponseWriter, r *http.Request, code int) {
	m := reExternalDB.FindStringSubmatch(r.URL.Path)
	namespace := m[1]
	log.Printf("  → register external DB  namespace=%q  code=%d", namespace, code)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	if code >= 400 {
		writeJSON(w, map[string]string{
			"error":  fmt.Sprintf("mock error (code %d)", code),
			"status": strconv.Itoa(code),
		})
	}
}

// handleApply serves POST /api/declarations/v1/apply.
// Returns a synchronous 200 with a COMPLETED status and a Validated condition.
func handleApply(w http.ResponseWriter, r *http.Request) {
	log.Printf("  → apply config (sync 200 COMPLETED)")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeJSON(w, map[string]interface{}{
		"status": "COMPLETED",
		"conditions": []map[string]string{
			{"type": "Validated", "state": "True", "reason": "OK", "message": ""},
		},
	})
}

// handleOpStatus serves GET /api/declarations/v1/operation/{id}/status.
func handleOpStatus(w http.ResponseWriter, r *http.Request) {
	m := reOpStatus.FindStringSubmatch(r.URL.Path)
	trackingID := m[1]
	log.Printf("  → operation status  trackingId=%q  → COMPLETED", trackingID)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeJSON(w, map[string]interface{}{
		"status":     "COMPLETED",
		"trackingId": trackingID,
		"conditions": []map[string]string{
			{"type": "DBCreated", "state": "True", "reason": "OK", "message": ""},
		},
	})
}

// ── helpers ───────────────────────────────────────────────────────────────────

func logRequest(r *http.Request) {
	username, _, _ := r.BasicAuth()
	log.Printf("← %s %s  auth-user=%q", r.Method, r.URL.Path, username)

	body, _ := io.ReadAll(r.Body)
	r.Body = io.NopCloser(strings.NewReader(string(body))) // restore for handlers

	if len(body) > 0 {
		var v any
		if err := json.Unmarshal(body, &v); err == nil {
			pretty, _ := json.MarshalIndent(v, "  ", "  ")
			log.Printf("  body:\n  %s", pretty)
		} else {
			log.Printf("  body: %s", body)
		}
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("  writeJSON error: %v", err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
