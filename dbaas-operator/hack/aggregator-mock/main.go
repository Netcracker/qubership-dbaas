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
// Authentication behaviour mirrors the real dbaas-aggregator:
//   - Credentials are loaded from users.json at DBAAS_SECURITY_CONFIGURATION_LOCATION
//     (default: /etc/dbaas/security), which is the same path used by dbaas-aggregator.
//   - Every request is validated with HTTP Basic Auth.
//   - Wrong credentials → 401 Unauthorized.
//   - Valid credentials but insufficient role → 403 Forbidden.
//
// Error responses are returned in full TmfErrorResponse format (NC.TMFErrorResponse.v1.0),
// matching what the real dbaas-aggregator returns for each HTTP error code.
//
// Environment variables:
//
//	PORT                              – listen port (default: 8080)
//	DBAAS_SECURITY_CONFIGURATION_LOCATION – directory containing users.json
//	                                    (default: /etc/dbaas/security).
//	                                    Falls back to DBAAS_USERNAME / DBAAS_PASSWORD
//	                                    env vars for local development without a Secret.
//	MOCK_RESPONSE_CODE                – default HTTP status code for PUT registration
//	                                    when no per-dbName rule matches (default: 200).
//	MOCK_RULES_FILE                   – path to a JSON file mapping dbName → MockRule
//	                                    (default: /config/rules.json). Missing = not an error.
package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/google/uuid"
)

// UserConfig mirrors the per-user entry in dbaas-aggregator's users.json.
type UserConfig struct {
	Password string   `json:"password"`
	Roles    []string `json:"roles"`
}

// MockRule fully describes the response for a specific dbName.
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

// TmfErrorResponse mirrors the NC.TMFErrorResponse.v1.0 format returned by
// the real dbaas-aggregator. The Java field "detail" is serialised as "message"
// via @JsonProperty("message").
type TmfErrorResponse struct {
	ID      string `json:"id"`
	Code    string `json:"code,omitempty"`
	Reason  string `json:"reason,omitempty"`
	Message string `json:"message,omitempty"`
	Status  string `json:"status"`
	Type    string `json:"@type"`
}

const tmfType = "NC.TMFErrorResponse.v1.0"

// authRuleUnauthorized is the fixed response for missing/wrong credentials (401).
// The security framework in the real dbaas-aggregator does not assign a CORE-DBAAS-*
// error code for 401 responses.
var authRuleUnauthorized = MockRule{
	HTTPCode: http.StatusUnauthorized,
	Message:  "Requested role is not allowed",
}

// authRuleForbidden is the fixed response when the authenticated user lacks the
// required role (403 at the auth layer, before any dbName is resolved).
var authRuleForbidden = MockRule{
	HTTPCode: http.StatusForbidden,
	Message:  "Access forbidden",
}

// Role constants mirror dbaas-aggregator's Constants.java.
const (
	roleDBClient         = "DB_CLIENT"
	roleNamespaceCleaner = "NAMESPACE_CLEANER"
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
	defaultCode := envInt("MOCK_RESPONSE_CODE", http.StatusOK)
	defaultRule := MockRule{HTTPCode: defaultCode}
	rulesFile := envOr("MOCK_RULES_FILE", "/config/rules.json")
	rules := loadRules(rulesFile)

	// Load users from users.json — same path as the real dbaas-aggregator.
	securityDir := envOr("DBAAS_SECURITY_CONFIGURATION_LOCATION", "/etc/dbaas/security")
	users := loadUsers(securityDir)

	log.Printf("mock dbaas-aggregator starting on :%s", port)
	log.Printf("  PUT  .../externally_manageable → default httpCode=%d  (%d per-dbName rules loaded)",
		defaultRule.HTTPCode, len(rules))
	log.Printf("  POST .../apply                 → 200 (sync)")
	log.Printf("  GET  .../operation/.../status  → 200 COMPLETED")

	if err := http.ListenAndServe(":"+port, handler(defaultRule, rules, users)); err != nil {
		log.Fatalf("listen: %v", err)
	}
}

// loadUsers reads users.json from the given directory.
// Format matches dbaas-aggregator: { "username": { "password": "...", "roles": [...] } }.
// Falls back to DBAAS_USERNAME / DBAAS_PASSWORD env vars for local development.
func loadUsers(dir string) map[string]UserConfig {
	path := filepath.Join(dir, "users.json")
	data, err := os.ReadFile(path)
	if err != nil {
		// Local development fallback: build a single-user map from env vars.
		u := os.Getenv("DBAAS_USERNAME")
		p := os.Getenv("DBAAS_PASSWORD")
		if u != "" {
			log.Printf("users.json not found at %q, using env vars DBAAS_USERNAME/DBAAS_PASSWORD", path)
			return map[string]UserConfig{
				u: {Password: p, Roles: []string{roleDBClient, roleNamespaceCleaner}},
			}
		}
		log.Fatalf("cannot read users.json at %q and DBAAS_USERNAME env var is not set: %v", path, err)
	}

	var users map[string]UserConfig
	if err := json.Unmarshal(data, &users); err != nil {
		log.Fatalf("parse users.json at %q: %v", path, err)
	}
	log.Printf("loaded %d users from %q", len(users), path)
	for u, cfg := range users {
		log.Printf("  user: %q  roles: %v", u, cfg.Roles)
	}
	return users
}

// loadRules reads a JSON file that maps dbName → MockRule.
// A missing file is not an error — the mock falls back to the global default code.
func loadRules(path string) map[string]MockRule {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("rules file %q not found or unreadable, using MOCK_RESPONSE_CODE only: %v", path, err)
		return nil
	}
	var rules map[string]MockRule
	if err := json.Unmarshal(data, &rules); err != nil {
		log.Fatalf("parse rules file %q: %v", path, err)
	}
	log.Printf("loaded %d dbName rules from %q", len(rules), path)
	for db, rule := range rules {
		log.Printf("  rule: dbName=%q → httpCode=%d tmfCode=%q reason=%q message=%q",
			db, rule.HTTPCode, rule.TmfCode, rule.Reason, rule.Message)
	}
	return rules
}

func handler(defaultRule MockRule, rules map[string]MockRule, users map[string]UserConfig) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		logRequest(r)

		switch {
		case reExternalDB.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			// Mirrors @RolesAllowed(DB_CLIENT) on addExternalDatabase.
			if !checkAuth(w, r, users, roleDBClient) {
				return
			}
			handleExternalDB(w, r, defaultRule, rules)

		case reApply.MatchString(r.URL.Path) && r.Method == http.MethodPost:
			if !checkAuth(w, r, users, roleDBClient) {
				return
			}
			handleApply(w, r)

		case reOpStatus.MatchString(r.URL.Path) && r.Method == http.MethodGet:
			if !checkAuth(w, r, users, roleDBClient) {
				return
			}
			handleOpStatus(w, r)

		default:
			log.Printf("  → 404 no route for %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})
	return mux
}

// checkAuth validates HTTP Basic Auth and role, mirroring dbaas-aggregator behaviour:
//   - missing / wrong credentials → 401 Unauthorized
//   - valid credentials but missing required role → 403 Forbidden
func checkAuth(w http.ResponseWriter, r *http.Request, users map[string]UserConfig, requiredRole string) bool {
	u, p, ok := r.BasicAuth()
	if !ok {
		log.Printf("  → 401 no Basic Auth header")
		writeTmfError(w, authRuleUnauthorized)
		return false
	}

	cfg, exists := users[u]
	if !exists || cfg.Password != p {
		log.Printf("  → 401 invalid credentials (user=%q)", u)
		writeTmfError(w, authRuleUnauthorized)
		return false
	}

	if requiredRole != "" && !hasRole(cfg.Roles, requiredRole) {
		log.Printf("  → 403 user=%q does not have role %q (has: %v)", u, requiredRole, cfg.Roles)
		writeTmfError(w, authRuleForbidden)
		return false
	}

	return true
}

func hasRole(roles []string, role string) bool {
	for _, r := range roles {
		if r == role {
			return true
		}
	}
	return false
}

// handleExternalDB serves PUT .../externally_manageable.
// The response rule is resolved from the per-dbName rules map, falling back to defaultRule.
// For error responses (4xx/5xx) a full TmfErrorResponse body is returned using the
// fields specified in the rule — no hardcoded templates.
func handleExternalDB(w http.ResponseWriter, r *http.Request, defaultRule MockRule, rules map[string]MockRule) {
	m := reExternalDB.FindStringSubmatch(r.URL.Path)
	namespace := m[1]

	// Parse dbName from the request body.
	var req struct {
		DbName string `json:"dbName"`
	}
	body, _ := io.ReadAll(r.Body)
	r.Body = io.NopCloser(strings.NewReader(string(body)))
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

// writeTmfError writes a complete error response using the fields from rule:
// sets Content-Type, optional WWW-Authenticate header (for 401), writes the HTTP
// status code, then a TmfErrorResponse JSON body. The id and @type fields are
// always generated; all other fields come directly from the rule.
func writeTmfError(w http.ResponseWriter, rule MockRule) {
	w.Header().Set("Content-Type", "application/json")
	if rule.HTTPCode == http.StatusUnauthorized {
		w.Header().Set("WWW-Authenticate", `Basic realm="dbaas-aggregator"`)
	}
	w.WriteHeader(rule.HTTPCode)
	tmf := TmfErrorResponse{
		ID:      uuid.NewString(),
		Code:    rule.TmfCode,
		Reason:  rule.Reason,
		Message: rule.Message,
		Status:  strconv.Itoa(rule.HTTPCode),
		Type:    tmfType,
	}
	log.Printf("  → %d  TMF code=%q reason=%q message=%q", rule.HTTPCode, tmf.Code, tmf.Reason, tmf.Message)
	writeJSON(w, tmf)
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
