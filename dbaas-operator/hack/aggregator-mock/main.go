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
// Authentication: every request must carry a non-empty Bearer token in the
// Authorization header (any non-empty token is accepted — the mock does not
// validate the token value, it only checks that one is present).
// Missing or empty token → 401 Unauthorized.
//
// Error responses are returned in full TmfErrorResponse format (NC.TMFErrorResponse.v1.0),
// matching what the real dbaas-aggregator returns for each HTTP error code.
//
// Environment variables:
//
//	PORT                – listen port (default: 8080)
//	MOCK_RESPONSE_CODE  – default HTTP status code for PUT registration
//	                      when no per-dbName rule matches (default: 200).
//	MOCK_RULES_FILE     – path to a JSON file mapping dbName → MockRule
//	                      (default: /config/rules.json). Missing = not an error.
//	MOCK_APPLY_RULES_FILE – path to a JSON file mapping microserviceName → MockRule
//	                      for POST /api/declarations/v1/apply (default: /config/apply-rules.json).
//	                      Missing = not an error.
//	                      For DbPolicy: no rule → 200 COMPLETED.
//	                      For DatabaseDeclaration: no rule → 202 IN_PROGRESS with trackingId.
//	MOCK_POLL_RULES_FILE – path to a JSON file mapping trackingId → MockPollRule
//	                      for GET /api/declarations/v1/operation/{id}/status
//	                      (default: /config/poll-rules.json). Missing = not an error.
//	                      No rule → 200 COMPLETED.
package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go-error-handling/v3/tmf"
)

// MockRule fully describes the response for a specific dbName or microserviceName.
// All TmfErrorResponse fields are specified here — the mock contains no hardcoded
// templates; the ConfigMap is the single source of truth for every response.
//
// For 2xx codes TmfCode/Reason/Message are ignored (no error body is returned).
// For 4xx/5xx codes omitted fields produce empty strings in the JSON output,
// which faithfully reproduces aggregator behaviour for codes without an ErrorCode.
type MockRule struct {
	HTTPCode int    `json:"httpCode"`
	TmfCode  string `json:"tmfCode,omitempty"` // e.g. "CORE-DBAAS-4010"
	Reason   string `json:"reason,omitempty"`
	Message  string `json:"message,omitempty"`
}

// MockPollRule describes the poll response for a specific trackingId.
// HTTPCode overrides the response code (e.g. 404). Status controls the payload.
type MockPollRule struct {
	// HTTPCode, when non-zero, returns a non-200 response (e.g. 404).
	HTTPCode int `json:"httpCode,omitempty"`
	// Status is the operation status to return: "COMPLETED", "FAILED", "TERMINATED",
	// "IN_PROGRESS", "NOT_STARTED". Defaults to "COMPLETED" when HTTPCode is 0.
	Status string `json:"status,omitempty"`
	// FailReason is included in the DataBaseCreated condition reason when Status=FAILED.
	FailReason string `json:"failReason,omitempty"`
}

// authRuleUnauthorized is the fixed response for a missing/empty Bearer token (401).
var authRuleUnauthorized = MockRule{
	HTTPCode: http.StatusUnauthorized,
	Message:  "Requested role is not allowed",
}

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
	defaultCode := envInt("MOCK_RESPONSE_CODE", http.StatusOK)
	defaultRule := MockRule{HTTPCode: defaultCode}
	rulesFile := envOr("MOCK_RULES_FILE", "/config/rules.json")
	rules := loadRules(rulesFile)
	applyRulesFile := envOr("MOCK_APPLY_RULES_FILE", "/config/apply-rules.json")
	applyRules := loadRules(applyRulesFile)
	pollRulesFile := envOr("MOCK_POLL_RULES_FILE", "/config/poll-rules.json")
	pollRules := loadPollRules(pollRulesFile)

	log.Printf("mock dbaas-aggregator starting on :%s", port)
	log.Printf("  PUT  .../externally_manageable → default httpCode=%d  (%d per-dbName rules loaded)",
		defaultRule.HTTPCode, len(rules))
	log.Printf("  POST .../apply                 → DbPolicy:200 / DatabaseDeclaration:202  (%d per-microserviceName rules loaded)", len(applyRules))
	log.Printf("  GET  .../operation/.../status  → default COMPLETED  (%d per-trackingId poll rules loaded)", len(pollRules))
	log.Printf("  auth: Bearer token required (any non-empty token accepted)")

	if err := http.ListenAndServe(":"+port, handler(defaultRule, rules, applyRules, pollRules)); err != nil {
		log.Fatalf("listen: %v", err)
	}
}

// loadRules reads a JSON file that maps a string key → MockRule.
// A missing file is not an error.
func loadRules(path string) map[string]MockRule {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("rules file %q not found or unreadable, using defaults: %v", path, err)
		return nil
	}
	var rules map[string]MockRule
	if err := json.Unmarshal(data, &rules); err != nil {
		log.Fatalf("parse rules file %q: %v", path, err)
	}
	log.Printf("loaded %d rules from %q", len(rules), path)
	for k, rule := range rules {
		log.Printf("  rule: key=%q → httpCode=%d tmfCode=%q reason=%q message=%q",
			k, rule.HTTPCode, rule.TmfCode, rule.Reason, rule.Message)
	}
	return rules
}

// loadPollRules reads a JSON file that maps trackingId → MockPollRule.
// A missing file is not an error.
func loadPollRules(path string) map[string]MockPollRule {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("poll-rules file %q not found or unreadable, using defaults: %v", path, err)
		return nil
	}
	var rules map[string]MockPollRule
	if err := json.Unmarshal(data, &rules); err != nil {
		log.Fatalf("parse poll-rules file %q: %v", path, err)
	}
	log.Printf("loaded %d poll rules from %q", len(rules), path)
	for k, rule := range rules {
		log.Printf("  poll-rule: trackingId=%q → httpCode=%d status=%q failReason=%q",
			k, rule.HTTPCode, rule.Status, rule.FailReason)
	}
	return rules
}

func handler(defaultRule MockRule, rules map[string]MockRule, applyRules map[string]MockRule, pollRules map[string]MockPollRule) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		body := logRequest(r)

		if !checkAuth(w, r) {
			return
		}

		switch {
		case reExternalDB.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			handleExternalDB(w, r, body, defaultRule, rules)

		case reApply.MatchString(r.URL.Path) && r.Method == http.MethodPost:
			handleApply(w, body, applyRules)

		case reOpStatus.MatchString(r.URL.Path) && r.Method == http.MethodGet:
			handleOpStatus(w, r, pollRules)

		default:
			log.Printf("  → 404 no route for %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})
	return mux
}

// checkAuth validates the Bearer token from the Authorization header.
// Any non-empty token is accepted — the mock does not validate the token value.
// Missing or empty token → 401 Unauthorized.
func checkAuth(w http.ResponseWriter, r *http.Request) bool {
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if token == "" {
		log.Printf("  → 401 missing or empty Bearer token")
		writeTmfError(w, authRuleUnauthorized)
		return false
	}
	return true
}

// handleExternalDB serves PUT .../externally_manageable.
func handleExternalDB(w http.ResponseWriter, r *http.Request, body []byte, defaultRule MockRule, rules map[string]MockRule) {
	m := reExternalDB.FindStringSubmatch(r.URL.Path)
	namespace := m[1]

	var req struct {
		DbName string `json:"dbName"`
	}
	_ = json.Unmarshal(body, &req)

	rule := defaultRule
	if req.DbName != "" {
		if matched, ok := rules[req.DbName]; ok {
			log.Printf("  → rule match dbName=%q → httpCode=%d  namespace=%q",
				req.DbName, matched.HTTPCode, namespace)
			rule = matched
		} else {
			log.Printf("  → no rule for dbName=%q, using default httpCode=%d  namespace=%q",
				req.DbName, defaultRule.HTTPCode, namespace)
		}
	} else {
		log.Printf("  → register external DB  namespace=%q  httpCode=%d (no dbName in body)", namespace, rule.HTTPCode)
	}

	if rule.HTTPCode >= 400 {
		writeTmfError(w, rule)
	} else {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(rule.HTTPCode)
	}
}

// handleApply serves POST /api/declarations/v1/apply.
//
// Behaviour by subKind (when no rule matches):
//   - "DbPolicy"             → 200 COMPLETED (synchronous)
//   - "DatabaseDeclaration"  → 202 IN_PROGRESS with deterministic trackingId
//
// An explicit rule in applyRules (keyed by microserviceName) overrides the default.
// Error rules (4xx/5xx) are returned as TmfErrorResponse regardless of subKind.
func handleApply(w http.ResponseWriter, body []byte, applyRules map[string]MockRule) {
	var req struct {
		SubKind  string `json:"subKind"`
		Metadata struct {
			MicroserviceName string `json:"microserviceName"`
		} `json:"metadata"`
	}
	_ = json.Unmarshal(body, &req)

	msName := req.Metadata.MicroserviceName

	// Explicit rule overrides default.
	if rule, ok := applyRules[msName]; ok {
		log.Printf("  → apply config  subKind=%q microserviceName=%q → httpCode=%d (rule)", req.SubKind, msName, rule.HTTPCode)
		if rule.HTTPCode >= 400 {
			writeTmfError(w, rule)
			return
		}
		// 2xx rule: honour the exact code.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(rule.HTTPCode)
		writeJSON(w, map[string]interface{}{
			"status":     "COMPLETED",
			"conditions": []map[string]string{{"type": "Validated", "state": "COMPLETED"}},
		})
		return
	}

	// Default behaviour depends on subKind.
	if req.SubKind == "DatabaseDeclaration" {
		// DatabaseDeclaration is always async: return 202 with a deterministic trackingId.
		trackingID := "tracking-" + msName
		log.Printf("  → apply config  subKind=DatabaseDeclaration microserviceName=%q → 202 IN_PROGRESS trackingId=%q (default)",
			msName, trackingID)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		writeJSON(w, map[string]interface{}{
			"status":     "IN_PROGRESS",
			"trackingId": trackingID,
			"conditions": []map[string]string{
				{"type": "Validated", "state": "COMPLETED"},
				{"type": "DataBaseCreated", "state": "IN_PROGRESS"},
			},
		})
		return
	}

	// DbPolicy and any other subKind: default 200 COMPLETED (synchronous).
	log.Printf("  → apply config  subKind=%q microserviceName=%q → 200 COMPLETED (default)", req.SubKind, msName)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeJSON(w, map[string]interface{}{
		"status":     "COMPLETED",
		"conditions": []map[string]string{{"type": "Validated", "state": "COMPLETED"}},
	})
}

// handleOpStatus serves GET /api/declarations/v1/operation/{id}/status.
//
// The response is resolved from pollRules keyed by trackingId.
// Missing rule → 200 COMPLETED (happy-path default).
func handleOpStatus(w http.ResponseWriter, r *http.Request, pollRules map[string]MockPollRule) {
	m := reOpStatus.FindStringSubmatch(r.URL.Path)
	trackingID := m[1]

	if rule, ok := pollRules[trackingID]; ok {
		log.Printf("  → operation status  trackingId=%q → rule: httpCode=%d status=%q", trackingID, rule.HTTPCode, rule.Status)

		// Non-200 response (e.g. 404).
		if rule.HTTPCode != 0 && rule.HTTPCode != http.StatusOK {
			w.WriteHeader(rule.HTTPCode)
			return
		}

		status := rule.Status
		if status == "" {
			status = "COMPLETED"
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)

		dbCreatedCondition := map[string]string{
			"type":  "DataBaseCreated",
			"state": status,
		}
		if rule.FailReason != "" {
			dbCreatedCondition["reason"] = rule.FailReason
			dbCreatedCondition["message"] = "Failed"
		}

		writeJSON(w, map[string]interface{}{
			"status":     status,
			"trackingId": trackingID,
			"conditions": []map[string]string{
				{"type": "Validated", "state": "COMPLETED"},
				dbCreatedCondition,
			},
		})
		return
	}

	// Default: COMPLETED.
	log.Printf("  → operation status  trackingId=%q → COMPLETED (default)", trackingID)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeJSON(w, map[string]interface{}{
		"status":     "COMPLETED",
		"trackingId": trackingID,
		"conditions": []map[string]string{
			{"type": "Validated", "state": "COMPLETED"},
			{"type": "DataBaseCreated", "state": "COMPLETED"},
		},
	})
}

// ── helpers ───────────────────────────────────────────────────────────────────

// logRequest logs the incoming request and returns the body bytes.
// The caller is responsible for passing the body to any handler that needs it.
func logRequest(r *http.Request) []byte {
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	tokenPreview := ""
	if len(token) > 8 {
		tokenPreview = token[:8] + "..."
	} else {
		tokenPreview = token
	}
	log.Printf("← %s %s  bearer=%q", r.Method, r.URL.Path, tokenPreview)

	body, _ := io.ReadAll(r.Body)

	if len(body) > 0 {
		var v any
		if err := json.Unmarshal(body, &v); err == nil {
			pretty, _ := json.MarshalIndent(v, "  ", "  ")
			log.Printf("  body:\n  %s", pretty)
		} else {
			log.Printf("  body: %s", body)
		}
	}

	return body
}

// writeTmfError writes a complete error response using the fields from rule.
func writeTmfError(w http.ResponseWriter, rule MockRule) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(rule.HTTPCode)
	status := strconv.Itoa(rule.HTTPCode)
	resp := tmf.Response{
		Id:      uuid.NewString(),
		Code:    rule.TmfCode,
		Reason:  rule.Reason,
		Message: rule.Message,
		Status:  &status,
		Type:    tmf.TypeV1_0,
	}
	log.Printf("  → %d  TMF code=%q reason=%q message=%q", rule.HTTPCode, resp.Code, resp.Reason, resp.Message)
	writeJSON(w, resp)
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
