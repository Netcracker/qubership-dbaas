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
// Authentication: every request must be authenticated either with HTTP Basic Auth
// (Basic mode, the default — operator runs with KUBERNETES_M2M_ENABLED=false) or a
// non-empty Bearer token (M2M mode, KUBERNETES_M2M_ENABLED=true). The mock validates
// neither value — it only checks that the operator authenticated somehow, so the same
// mock serves both modes with no reconfiguration. No credentials → 401 Unauthorized.
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
//	                      For DatabaseAccessPolicy: no rule → 200 COMPLETED.
//	                      For InternalDatabase: no rule → 202 IN_PROGRESS with trackingId.
//	MOCK_POLL_RULES_FILE – path to a JSON file mapping trackingId → MockPollRule
//	                      for GET /api/declarations/v1/operation/{id}/status
//	                      (default: /config/poll-rules.json). Missing = not an error.
//	                      No rule → 200 COMPLETED.
//	MOCK_CHANGED_FILE    – path to a JSON array of ChangedEntry for the rotation
//	                      feed GET /api/v3/dbaas/databases/changed
//	                      (default: /config/changed.json). Missing = empty feed.
//	                      The since-less seed call always reports no history
//	                      (highWaterMark=null), so the operator seeds at epoch and
//	                      replays the configured entries once — simulating rotations
//	                      that occur after the operator starts.
//	MOCK_GETBYCLASSIFIER_FILE – path to a JSON file mapping originService →
//	                      connectionProperties (map) for
//	                      POST /api/v3/dbaas/{ns}/databases/get-by-classifier/{type}
//	                      (default: /config/get-by-classifier.json). No rule →
//	                      a synthetic default property set.
//	MOCK_CREATEDB_RULES_FILE – path to a JSON file mapping originService → MockRule
//	                      for the get-or-create database call
//	                      PUT /api/v3/dbaas/{namespace}/databases — the operator issues
//	                      it to materialize the concrete {scope=tenant, tenantId} database
//	                      for a tenant declaration that pins a tenantId
//	                      (default: /config/create-db-rules.json). Missing = not an error;
//	                      no rule → 200 with a synthetic database descriptor.
package main

import (
	"encoding/json"
	"io"
	"log"
	"maps"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

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

	// PUT /api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices
	reMicroserviceBalancingRule = regexp.MustCompile(
		`^/api/v3/dbaas/([^/]+)/physical_databases/rules/onMicroservices$`)

	// PUT /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}
	reNamespaceBalancingRule = regexp.MustCompile(
		`^/api/v3/dbaas/([^/]+)/physical_databases/balancing/rules/([^/]+)$`)

	// PUT|DELETE /api/v3/dbaas/balancing/rules/permanent
	rePermanentBalancingRule = regexp.MustCompile(`^/api/v3/dbaas/balancing/rules/permanent$`)

	// GET /api/v3/dbaas/databases/changed
	reChanged = regexp.MustCompile(`^/api/v3/dbaas/databases/changed$`)

	// POST /api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}
	reGetByClassifier = regexp.MustCompile(
		`^/api/v3/dbaas/([^/]+)/databases/get-by-classifier/([^/]+)$`)

	// PUT /api/v3/dbaas/{namespace}/databases — get-or-create database (the
	// operator's pinned-tenant materialization call). Anchored on a bare
	// /databases suffix so it never matches the longer .../databases/registration/…
	// or .../databases/get-by-classifier/… paths.
	reCreateDB = regexp.MustCompile(`^/api/v3/dbaas/([^/]+)/databases$`)
)

// ChangedEntry is one database in the rotation feed (changed.json). Its JSON tags
// mirror the operator's ChangedDatabaseRef, so an entry serializes straight onto
// the wire. lastRotatedAt is an RFC3339 timestamp.
type ChangedEntry struct {
	Id            string         `json:"id"`
	Namespace     string         `json:"namespace"`
	Type          string         `json:"type"`
	LastRotatedAt string         `json:"lastRotatedAt"`
	Classifier    map[string]any `json:"classifier"`
}

// dbStore is the mock's in-memory record of databases materialized via the get-or-create
// call (PUT .../databases). It lets get-by-classifier emulate the real aggregator's
// lazy-tenant behaviour: a tenant database exists only after it has been created, so a
// DatabaseSecretClaim for a not-yet-materialized tenant gets a 404 (DatabaseNotFound) —
// exactly what the operator's pinned-tenant materialization is there to prevent. State is
// per-process and lost on restart.
type dbStore struct {
	mu   sync.Mutex
	seen map[string]bool
}

func newDBStore() *dbStore { return &dbStore{seen: map[string]bool{}} }

func (s *dbStore) add(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seen[key] = true
}

func (s *dbStore) has(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.seen[key]
}

// classifierKey is a deterministic identity for a (classifier, type) pair. Both the
// get-or-create and get-by-classifier handlers compute it from the same flat classifier the
// operator sends (ClassifierFlatMap), so a database created by one is found by the other.
// json.Marshal sorts map keys, making the encoding stable.
func classifierKey(classifier map[string]any, dbType string) string {
	b, _ := json.Marshal(classifier)
	return dbType + "|" + string(b)
}

// isTenant reports whether a classifier is tenant-scoped (scope=tenant), matched
// case-insensitively like the operator and aggregator do.
func isTenant(classifier map[string]any) bool {
	scope, _ := classifier["scope"].(string)
	return strings.EqualFold(scope, "tenant")
}

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
	changedFile := envOr("MOCK_CHANGED_FILE", "/config/changed.json")
	changed := loadChanged(changedFile)
	gbcFile := envOr("MOCK_GETBYCLASSIFIER_FILE", "/config/get-by-classifier.json")
	gbcRules := loadGbcRules(gbcFile)
	createDbFile := envOr("MOCK_CREATEDB_RULES_FILE", "/config/create-db-rules.json")
	createDbRules := loadRules(createDbFile)

	log.Printf("mock dbaas-aggregator starting on :%s", port)
	log.Printf("  PUT  .../externally_manageable → default httpCode=%d  (%d per-dbName rules loaded)",
		defaultRule.HTTPCode, len(rules))
	log.Printf("  POST .../apply → DatabaseAccessPolicy:200 / InternalDatabase:202 (%d per-microserviceName rules loaded)",
		len(applyRules))
	log.Printf("  GET  .../operation/.../status → default COMPLETED (%d per-trackingId poll rules loaded)",
		len(pollRules))
	log.Printf("  POST .../get-by-classifier/{type} → 200 connectionProperties (%d per-originService rules loaded)",
		len(gbcRules))
	log.Printf("  PUT  .../databases → 200 get-or-create database (%d per-originService create-db rules loaded)",
		len(createDbRules))
	log.Printf("  GET  .../databases/changed → rotation feed (%d changed entries loaded; seed reports no history)",
		len(changed))
	log.Printf("  auth: Basic Auth or Bearer token required (value not validated — both modes accepted)")

	h := handler(defaultRule, rules, applyRules, pollRules, changed, gbcRules, createDbRules)
	if err := http.ListenAndServe(":"+port, h); err != nil {
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

// loadChanged reads the rotation feed (a JSON array of ChangedEntry).
// A missing file is not an error — it yields an empty feed.
func loadChanged(path string) []ChangedEntry {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("changed file %q not found or unreadable, rotation feed is empty: %v", path, err)
		return nil
	}
	var entries []ChangedEntry
	if err := json.Unmarshal(data, &entries); err != nil {
		log.Fatalf("parse changed file %q: %v", path, err)
	}
	log.Printf("loaded %d changed entries from %q", len(entries), path)
	for _, e := range entries {
		log.Printf("  changed: id=%q namespace=%q type=%q lastRotatedAt=%q classifier=%v",
			e.Id, e.Namespace, e.Type, e.LastRotatedAt, e.Classifier)
	}
	return entries
}

// loadGbcRules reads a JSON file mapping originService → connectionProperties.
// A missing file is not an error — get-by-classifier then falls back to a
// synthetic default property set for every lookup.
func loadGbcRules(path string) map[string]map[string]any {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("get-by-classifier file %q not found or unreadable, using synthetic defaults: %v", path, err)
		return nil
	}
	var rules map[string]map[string]any
	if err := json.Unmarshal(data, &rules); err != nil {
		log.Fatalf("parse get-by-classifier file %q: %v", path, err)
	}
	log.Printf("loaded %d get-by-classifier rules from %q", len(rules), path)
	for k := range rules {
		log.Printf("  get-by-classifier rule: originService=%q", k)
	}
	return rules
}

func handler(
	defaultRule MockRule, rules map[string]MockRule,
	applyRules map[string]MockRule, pollRules map[string]MockPollRule,
	changed []ChangedEntry, gbcRules map[string]map[string]any,
	createDbRules map[string]MockRule,
) http.Handler {
	mux := http.NewServeMux()
	// store records databases materialized via PUT .../databases so get-by-classifier
	// can emulate the real aggregator's lazy-tenant 404 before materialization.
	store := newDBStore()
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

		case reChanged.MatchString(r.URL.Path) && r.Method == http.MethodGet:
			handleChanged(w, r, changed)

		case reGetByClassifier.MatchString(r.URL.Path) && r.Method == http.MethodPost:
			handleGetByClassifier(w, r, body, gbcRules, store)

		case reCreateDB.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			handleCreateDatabase(w, r, body, createDbRules, store)

		case reMicroserviceBalancingRule.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			handleMicroserviceBalancingRule(w, r)

		case reNamespaceBalancingRule.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			handleNamespaceBalancingRule(w, r)

		case rePermanentBalancingRule.MatchString(r.URL.Path) && r.Method == http.MethodPut:
			handlePermanentBalancingRule(w, false)

		case rePermanentBalancingRule.MatchString(r.URL.Path) && r.Method == http.MethodDelete:
			handlePermanentBalancingRule(w, true)

		default:
			log.Printf("  → 404 no route for %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})
	return mux
}

// handleMicroserviceBalancingRule accepts on-microservice balancing rule updates.
// The mock only validates route/auth and does not simulate physical DB label resolution.
func handleMicroserviceBalancingRule(w http.ResponseWriter, r *http.Request) {
	m := reMicroserviceBalancingRule.FindStringSubmatch(r.URL.Path)
	namespace := m[1]
	log.Printf("  -> microservice balancing rules accepted namespace=%q", namespace)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_, _ = w.Write([]byte("[]"))
}

// handleNamespaceBalancingRule accepts namespace balancing rule create/update.
// The mock does not validate physicalDatabaseId existence.
func handleNamespaceBalancingRule(w http.ResponseWriter, r *http.Request) {
	m := reNamespaceBalancingRule.FindStringSubmatch(r.URL.Path)
	namespace := m[1]
	ruleName := m[2]
	log.Printf("  -> namespace balancing rule accepted namespace=%q ruleName=%q", namespace, ruleName)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
}

// handlePermanentBalancingRule accepts permanent balancing rule create/update/delete.
// The mock does not persist state; it exists to exercise operator reconciliation.
func handlePermanentBalancingRule(w http.ResponseWriter, delete bool) {
	action := "applied"
	if delete {
		action = "deleted"
	}
	log.Printf("  -> permanent balancing rules %s", action)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("[]"))
}

// checkAuth accepts a request authenticated with EITHER HTTP Basic Auth (Basic mode)
// or a non-empty Bearer token (M2M mode). The mock validates neither value — it only
// requires that the operator authenticated somehow, so it is mode-agnostic. A request
// with neither → 401 Unauthorized.
func checkAuth(w http.ResponseWriter, r *http.Request) bool {
	if user, _, ok := r.BasicAuth(); ok && user != "" {
		return true
	}
	if token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "); token != "" {
		return true
	}
	log.Printf("  → 401 missing credentials (neither Basic Auth nor Bearer token)")
	writeTmfError(w, authRuleUnauthorized)
	return false
}

// handleExternalDB serves PUT .../externally_manageable.
func handleExternalDB(
	w http.ResponseWriter, r *http.Request,
	body []byte, defaultRule MockRule, rules map[string]MockRule,
) {
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
		log.Printf("  → apply config  subKind=%q microserviceName=%q → httpCode=%d (rule)",
			req.SubKind, msName, rule.HTTPCode)
		if rule.HTTPCode >= 400 {
			writeTmfError(w, rule)
			return
		}
		// 2xx rule: honour the exact code.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(rule.HTTPCode)
		writeJSON(w, map[string]any{
			"status":     "COMPLETED",
			"conditions": []map[string]string{{"type": "Validated", "state": "COMPLETED"}},
		})
		return
	}

	// Default behaviour depends on subKind.
	if req.SubKind == "DatabaseDeclaration" {
		// InternalDatabase is always async: return 202 with a deterministic trackingId.
		trackingID := "tracking-" + msName
		log.Printf("  → apply config  subKind=DatabaseDeclaration microserviceName=%q"+
			" → 202 IN_PROGRESS trackingId=%q (default)",
			msName, trackingID)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		writeJSON(w, map[string]any{
			"status":     "IN_PROGRESS",
			"trackingId": trackingID,
			"conditions": []map[string]string{
				{"type": "Validated", "state": "COMPLETED"},
				{"type": "DataBaseCreated", "state": "IN_PROGRESS"},
			},
		})
		return
	}

	// DatabaseAccessPolicy and any other subKind: default 200 COMPLETED (synchronous).
	log.Printf("  → apply config  subKind=%q microserviceName=%q → 200 COMPLETED (default)", req.SubKind, msName)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeJSON(w, map[string]any{
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
		log.Printf("  → operation status  trackingId=%q → rule: httpCode=%d status=%q",
			trackingID, rule.HTTPCode, rule.Status)

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

		writeJSON(w, map[string]any{
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
	writeJSON(w, map[string]any{
		"status":     "COMPLETED",
		"trackingId": trackingID,
		"conditions": []map[string]string{
			{"type": "Validated", "state": "COMPLETED"},
			{"type": "DataBaseCreated", "state": "COMPLETED"},
		},
	})
}

// handleChanged serves GET /api/v3/dbaas/databases/changed — the rotation feed.
//
// The since-less seed call (no sinceTs) always reports no rotation history
// (highWaterMark=null), so the operator seeds its cursor at epoch and then
// replays the configured changed.json entries on the next poll — simulating
// rotations that happen after the operator starts. A cursor call returns the
// entries with (lastRotatedAt, id) strictly greater than the cursor, ordered
// ascending, honouring an optional limit. Once the operator's cursor passes the
// newest entry the feed returns nothing and the poller converges.
func handleChanged(w http.ResponseWriter, r *http.Request, changed []ChangedEntry) {
	sinceTs := r.URL.Query().Get("sinceTs")
	sinceID := r.URL.Query().Get("sinceId")

	w.Header().Set("Content-Type", "application/json")

	if sinceTs == "" {
		log.Printf("  → changed feed seed → highWaterMark=null items=[] (replays %d at epoch)", len(changed))
		writeJSON(w, map[string]any{"items": []ChangedEntry{}, "highWaterMark": nil})
		return
	}

	since, err := parseTS(sinceTs)
	if err != nil {
		log.Printf("  → changed feed: bad sinceTs=%q (%v), treating as epoch", sinceTs, err)
		since = time.Time{}
	}

	items := []ChangedEntry{}
	for _, e := range changed {
		t, perr := parseTS(e.LastRotatedAt)
		if perr != nil {
			log.Printf("  → changed feed: skipping entry id=%q with bad lastRotatedAt=%q: %v", e.Id, e.LastRotatedAt, perr)
			continue
		}
		if t.After(since) || (t.Equal(since) && e.Id > sinceID) {
			items = append(items, e)
		}
	}
	sort.Slice(items, func(i, j int) bool {
		ti, _ := parseTS(items[i].LastRotatedAt)
		tj, _ := parseTS(items[j].LastRotatedAt)
		if ti.Equal(tj) {
			return items[i].Id < items[j].Id
		}
		return ti.Before(tj)
	})

	if limit := atoiOr(r.URL.Query().Get("limit"), 0); limit > 0 && len(items) > limit {
		items = items[:limit]
	}

	log.Printf("  → changed feed since=(%s,%s) → %d item(s)", sinceTs, sinceID, len(items))
	writeJSON(w, map[string]any{
		"items":         items,
		"highWaterMark": highWaterMark(changed),
	})
}

// handleGetByClassifier serves POST /api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}.
// It returns 200 with a DatabaseResponseV3SingleCP-shaped body. connectionProperties
// come from the per-originService rule when present, otherwise from a synthetic
// default. Editing a rule's password (and restarting the mock) lets a subsequent
// rotation fan-out surface a real SecretRotated; the stable default yields the
// content-aware no-op (SecretUpToDate) on re-fetch.
func handleGetByClassifier(
	w http.ResponseWriter, r *http.Request, body []byte,
	gbcRules map[string]map[string]any, store *dbStore,
) {
	m := reGetByClassifier.FindStringSubmatch(r.URL.Path)
	namespace, dbType := m[1], m[2]

	var req struct {
		Classifier    map[string]any `json:"classifier"`
		OriginService string         `json:"originService"`
		UserRole      string         `json:"userRole"`
	}
	_ = json.Unmarshal(body, &req)

	key := req.OriginService
	if key == "" {
		key, _ = req.Classifier["microserviceName"].(string)
	}
	role := req.UserRole
	if role == "" {
		role = "admin"
	}

	props, ok := gbcRules[key]
	switch {
	case ok && len(props) > 0:
		log.Printf("  → get-by-classifier originService=%q type=%q role=%q → 200 (rule props)", key, dbType, role)
	case isTenant(req.Classifier) && !store.has(classifierKey(req.Classifier, dbType)):
		// Tenant database that was never materialized (no get-or-create yet) — the real
		// aggregator returns DatabaseNotFound. This is the gap the operator's pinned-tenant
		// materialization closes; without it the claim would wait here forever.
		log.Printf("  → get-by-classifier originService=%q type=%q tenantId=%v → 404 (tenant database not materialized)",
			key, dbType, req.Classifier["tenantId"])
		writeTmfError(w, MockRule{
			HTTPCode: http.StatusNotFound,
			TmfCode:  "CORE-DBAAS-4001",
			Reason:   "Database not found",
			Message:  "Database not found by classifier and type",
		})
		return
	default:
		props = defaultConnProps(role)
		log.Printf("  → get-by-classifier originService=%q type=%q role=%q → 200 (default props)", key, dbType, role)
	}

	// Always surface the requested role without mutating the shared rule map.
	if _, has := props["role"]; !has {
		merged := make(map[string]any, len(props)+1)
		maps.Copy(merged, props)
		merged["role"] = role
		props = merged
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeJSON(w, map[string]any{
		"id":                   "db-" + key,
		"name":                 stringOr(props["name"], key+"-db"),
		"namespace":            namespace,
		"type":                 dbType,
		"connectionProperties": props,
	})
}

// defaultConnProps is the synthetic connection-property set returned by
// get-by-classifier when no per-originService rule matches. It is stable, so a
// rotation fan-out re-fetch is a content-aware no-op (SecretUpToDate).
func defaultConnProps(role string) map[string]any {
	return map[string]any{
		"host":     "pg.example.com",
		"port":     5432,
		"name":     "app_db",
		"username": "app_user",
		"password": "p-static",
		"url":      "jdbc:postgresql://pg.example.com:5432/app_db",
		"role":     role,
	}
}

// handleCreateDatabase serves PUT /api/v3/dbaas/{namespace}/databases — the get-or-create
// database call the operator issues to materialize a concrete {scope=tenant, tenantId}
// database for a tenant declaration that pins a tenantId. The response code comes from the
// per-originService createDbRules rule when present (e.g. to simulate a 500); otherwise the
// mock returns 200 with a DatabaseResponseV3-shaped body. The operator ignores the body and
// inspects only the status code, so a minimal descriptor is enough.
func handleCreateDatabase(
	w http.ResponseWriter, r *http.Request, body []byte,
	createDbRules map[string]MockRule, store *dbStore,
) {
	m := reCreateDB.FindStringSubmatch(r.URL.Path)
	namespace := m[1]

	var req struct {
		Classifier    map[string]any `json:"classifier"`
		Type          string         `json:"type"`
		OriginService string         `json:"originService"`
	}
	_ = json.Unmarshal(body, &req)

	key := req.OriginService
	if key == "" {
		key, _ = req.Classifier["microserviceName"].(string)
	}
	tenantID, _ := req.Classifier["tenantId"].(string)

	if rule, ok := createDbRules[key]; ok {
		log.Printf("  → create database originService=%q tenantId=%q namespace=%q → httpCode=%d (rule)",
			key, tenantID, namespace, rule.HTTPCode)
		if rule.HTTPCode >= 400 {
			writeTmfError(w, rule)
			return
		}
		store.add(classifierKey(req.Classifier, req.Type))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(rule.HTTPCode)
		writeCreatedDatabase(w, namespace, req.Type, key, tenantID)
		return
	}

	store.add(classifierKey(req.Classifier, req.Type))
	log.Printf("  → create database originService=%q tenantId=%q namespace=%q → 200 (default, recorded)",
		key, tenantID, namespace)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	writeCreatedDatabase(w, namespace, req.Type, key, tenantID)
}

// writeCreatedDatabase writes a minimal DatabaseResponseV3-shaped success body for the
// get-or-create call. The operator does not read it (it checks only the status code); it
// exists so the mock returns a well-formed JSON object rather than an empty body.
func writeCreatedDatabase(w http.ResponseWriter, namespace, dbType, originService, tenantID string) {
	name := originService + "-db"
	if tenantID != "" {
		name = originService + "-" + tenantID + "-db"
	}
	writeJSON(w, map[string]any{
		"id":                   "db-" + name,
		"name":                 name,
		"namespace":            namespace,
		"type":                 dbType,
		"connectionProperties": defaultConnProps("admin"),
	})
}

// ── helpers ───────────────────────────────────────────────────────────────────

// logRequest logs the incoming request and returns the body bytes.
// The caller is responsible for passing the body to any handler that needs it.
func logRequest(r *http.Request) []byte {
	auth := "none"
	if user, _, ok := r.BasicAuth(); ok && user != "" {
		auth = "basic user=" + user
	} else if token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "); token != "" {
		preview := token
		if len(token) > 8 {
			preview = token[:8] + "..."
		}
		auth = "bearer=" + preview
	}
	log.Printf("← %s %s  auth=%s", r.Method, r.URL.Path, auth)

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

func atoiOr(s string, fallback int) int {
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return fallback
}

func stringOr(v any, fallback string) string {
	if s, ok := v.(string); ok && s != "" {
		return s
	}
	return fallback
}

// parseTS parses an RFC3339 timestamp, tolerating both the nano and second forms.
func parseTS(s string) (time.Time, error) {
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t, nil
	}
	return time.Parse(time.RFC3339, s)
}

// highWaterMark returns the greatest (lastRotatedAt, id) across the feed as a
// ChangeCursor-shaped map, or nil when the feed is empty.
func highWaterMark(changed []ChangedEntry) any {
	var maxT time.Time
	var maxID string
	found := false
	for _, e := range changed {
		t, err := parseTS(e.LastRotatedAt)
		if err != nil {
			continue
		}
		if !found || t.After(maxT) || (t.Equal(maxT) && e.Id > maxID) {
			maxT, maxID, found = t, e.Id, true
		}
	}
	if !found {
		return nil
	}
	return map[string]any{
		"lastRotatedAt": maxT.UTC().Format(time.RFC3339Nano),
		"id":            maxID,
	}
}
