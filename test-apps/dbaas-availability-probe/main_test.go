package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

// evaluate-probes.sh orders samples against the transition boundary with plain string comparison, so
// every recorded timestamp must keep a fixed-width 9-digit fractional-second field — a variable-width
// field (as time.RFC3339Nano produces by stripping trailing zeros) can sort out of chronological order.
var fixedWidthTimestamp = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z$`)

func TestRunProbe_TimestampIsFixedWidth(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK) // a round second, worst case for trailing-zero stripping
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, "aggregator-ready", checkReady(client, srv.URL))

	if !fixedWidthTimestamp.MatchString(res.Timestamp) {
		t.Fatalf("expected a fixed-width 9-digit fractional-second timestamp, got %q", res.Timestamp)
	}
}

func decodeResults(t *testing.T, buf *strings.Builder) []probeResult {
	t.Helper()
	var out []probeResult
	for _, line := range strings.Split(strings.TrimSpace(buf.String()), "\n") {
		if line == "" {
			continue
		}
		var r probeResult
		if err := json.Unmarshal([]byte(line), &r); err != nil {
			t.Fatalf("output line is not valid JSON: %q: %v", line, err)
		}
		out = append(out, r)
	}
	return out
}

func TestCheckHealth_UpSucceeds(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, "aggregator-health", checkHealth(client, srv.URL))

	if !res.Success {
		t.Fatalf("expected success, got error=%q", res.Error)
	}
	if res.HTTPCode != http.StatusOK {
		t.Fatalf("expected httpCode 200, got %d", res.HTTPCode)
	}
}

func TestCheckHealth_ProblemWithHTTP200Fails(t *testing.T) {
	// The aggregator returns HTTP 200 even when its own reported status is "PROBLEM" — the status code
	// alone must never be treated as success.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"PROBLEM"}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, "aggregator-health", checkHealth(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure for status=PROBLEM despite HTTP 200")
	}
	if res.HTTPCode != http.StatusOK {
		t.Fatalf("expected httpCode 200 recorded even on a failed probe, got %d", res.HTTPCode)
	}
	if res.Error == "" {
		t.Fatalf("expected a non-empty error message")
	}
}

func TestCheckReady_Non200Fails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, "aggregator-ready", checkReady(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure for HTTP 503")
	}
	if res.HTTPCode != http.StatusServiceUnavailable {
		t.Fatalf("expected httpCode 503, got %d", res.HTTPCode)
	}
}

func TestCheckReady_TimeoutIsRecorded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: 20 * time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	res := runProbe(ctx, &buf, &mu, "aggregator-ready", checkReady(client, srv.URL))

	if res.Success {
		t.Fatalf("expected a client-timeout failure to be recorded as unsuccessful")
	}
	if res.HTTPCode != 0 {
		t.Fatalf("expected httpCode 0 (no response received) on timeout, got %d", res.HTTPCode)
	}
	if res.Error == "" {
		t.Fatalf("expected a non-empty error message for the timeout")
	}
}

func TestCheckReady_ConnectionRefusedIsRecorded(t *testing.T) {
	// Bind a listener and close it immediately to obtain a port nothing is listening on.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve a local port: %v", err)
	}
	addr := l.Addr().String()
	_ = l.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, "aggregator-ready", checkReady(client, "http://"+addr))

	if res.Success {
		t.Fatalf("expected connection-refused to be recorded as unsuccessful")
	}
	if res.HTTPCode != 0 {
		t.Fatalf("expected httpCode 0 (no response received) on connection refusal, got %d", res.HTTPCode)
	}
	if res.Error == "" {
		t.Fatalf("expected a non-empty error message for the connection refusal")
	}
}

// TestLoopContinuesAfterFailure drives runLoop against a server that fails every other request and
// asserts the loop keeps producing samples past the first failure — a failed probe must never stop
// sampling, or the final report would show a gap instead of the true outage window.
func TestLoopContinuesAfterFailure(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls%2 == 0 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}

	ctx, cancel := context.WithTimeout(context.Background(), 55*time.Millisecond)
	defer cancel()
	runLoop(ctx, &buf, &mu, "aggregator-ready", checkReady(client, srv.URL), 10*time.Millisecond, time.Second)

	if calls < 4 {
		t.Fatalf("expected at least 4 probe attempts across pass/fail cycles, got %d", calls)
	}
	results := decodeResults(t, &buf)
	if len(results) < 4 {
		t.Fatalf("expected at least 4 recorded results, got %d", len(results))
	}
	sawSuccess, sawFailure := false, false
	for _, r := range results {
		if r.Success {
			sawSuccess = true
		} else {
			sawFailure = true
		}
	}
	if !sawSuccess || !sawFailure {
		t.Fatalf("expected both successes and failures across the loop, got sawSuccess=%v sawFailure=%v", sawSuccess, sawFailure)
	}
}

func TestCheckClassifier_NeverLogsCredentialsOrBody(t *testing.T) {
	const username = "cluster-dba"
	const password = "s3cr3t-test-password"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u, p, ok := r.BasicAuth()
		if !ok || u != username || p != password {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		var request struct {
			UserRole string `json:"userRole"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil || request.UserRole != "admin" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{
			"id": "11111111-1111-1111-1111-111111111111",
			"name": "some-db",
			"namespace": "dbaas",
			"type": "postgresql",
			"classifier": {"microserviceName": "go-test-app-service", "scope": "service", "namespace": "dbaas"},
			"connectionProperties": [{"username": "` + username + `", "password": "` + password + `", "url": "jdbc:postgresql://pg-patroni:5432/some-db"}]
		}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	fn := checkClassifier(client, srv.URL, "dbaas", "postgresql", "go-test-app-service", "service", username, password)
	res := runProbe(context.Background(), &buf, &mu, "dbaas-classifier", fn)

	if !res.Success {
		t.Fatalf("expected success, got error=%q", res.Error)
	}
	output := buf.String()
	if strings.Contains(output, password) {
		t.Fatalf("probe output must never contain the DBaaS password, got: %s", output)
	}
	if strings.Contains(output, "connectionProperties") || strings.Contains(output, "jdbc:postgresql") {
		t.Fatalf("probe output must never contain the raw classifier response body, got: %s", output)
	}
}

func TestCheckSamplePing_UnexpectedPayloadFails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok","result":0}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, "sample-postgres-ping", checkSamplePing(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure when result != 1")
	}

	results := decodeResults(t, &buf)
	if len(results) != 1 || results[0].Probe != "sample-postgres-ping" {
		t.Fatalf("expected exactly one sample-postgres-ping result line, got %v", results)
	}
}

// --- waitReadyRetry (fixture readiness: aggregator + sample ping) ---

func testWaitReadyConfig() config {
	return config{requestTimeout: time.Second}
}

func TestWaitReadyRetry_SamplePingFailsThenSucceeds(t *testing.T) {
	aggSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer aggSrv.Close()

	var sampleCalls int
	sampleSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sampleCalls++
		if sampleCalls < 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok","result":1}`))
	}))
	defer sampleSrv.Close()

	client := &http.Client{Timeout: time.Second}
	ready := checkReady(client, aggSrv.URL)
	ping := checkSamplePing(client, sampleSrv.URL)

	err := waitReadyRetry(testWaitReadyConfig(), ready, ping, 5, time.Millisecond)
	if err != nil {
		t.Fatalf("expected eventual success once the sample ping recovers, got: %v", err)
	}
	if sampleCalls < 3 {
		t.Fatalf("expected at least 3 sample-service calls (2 failures + 1 success), got %d", sampleCalls)
	}
}

func TestWaitReadyRetry_ExhaustsRetriesOnPersistentFailure(t *testing.T) {
	aggSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer aggSrv.Close()

	var sampleCalls int
	sampleSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sampleCalls++
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer sampleSrv.Close()

	client := &http.Client{Timeout: time.Second}
	ready := checkReady(client, aggSrv.URL)
	ping := checkSamplePing(client, sampleSrv.URL)

	const maxAttempts = 3
	err := waitReadyRetry(testWaitReadyConfig(), ready, ping, maxAttempts, time.Millisecond)
	if err == nil {
		t.Fatalf("expected retry exhaustion to return an error")
	}
	if sampleCalls != maxAttempts {
		t.Fatalf("expected exactly %d sample-service calls (one per attempt), got %d", maxAttempts, sampleCalls)
	}
}

func TestWaitReadyRetry_AggregatorNotReadyNeverCallsSample(t *testing.T) {
	aggSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer aggSrv.Close()

	var sampleCalls int
	sampleSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sampleCalls++
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok","result":1}`))
	}))
	defer sampleSrv.Close()

	client := &http.Client{Timeout: time.Second}
	ready := checkReady(client, aggSrv.URL)
	ping := checkSamplePing(client, sampleSrv.URL)

	err := waitReadyRetry(testWaitReadyConfig(), ready, ping, 3, time.Millisecond)
	if err == nil {
		t.Fatalf("expected an error while the aggregator is not ready")
	}
	if !strings.Contains(err.Error(), "aggregator not ready") {
		t.Fatalf("expected the error to identify the aggregator as the cause, got: %v", err)
	}
	if sampleCalls != 0 {
		t.Fatalf("expected the sample-service ping to never be called while the aggregator is down, got %d calls", sampleCalls)
	}
}

func TestWaitReadyRetry_ErrorNeverContainsCredentialsOrResponseBody(t *testing.T) {
	aggSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer aggSrv.Close()

	const leakedPassword = "s3cr3t-test-password"
	sampleSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// status/result are the only fields checkSamplePing decodes; password/connectionUrl must never
		// reach the returned error even though the body contains them.
		_, _ = w.Write([]byte(`{"status":"error","result":0,"password":"` + leakedPassword +
			`","connectionUrl":"jdbc:postgresql://host/db?password=` + leakedPassword + `"}`))
	}))
	defer sampleSrv.Close()

	client := &http.Client{Timeout: time.Second}
	ready := checkReady(client, aggSrv.URL)
	ping := checkSamplePing(client, sampleSrv.URL)

	err := waitReadyRetry(testWaitReadyConfig(), ready, ping, 1, time.Millisecond)
	if err == nil {
		t.Fatalf("expected an error for the malformed ping payload")
	}
	if strings.Contains(err.Error(), leakedPassword) {
		t.Fatalf("error must never contain the sample service's response body, got: %v", err)
	}
}
