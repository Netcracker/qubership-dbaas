package probe

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

// Recorded timestamps use the same fixed-width fractional-second field as the measurement
// boundaries.
var fixedWidthTimestamp = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z$`)

func TestRunProbe_TimestampIsFixedWidth(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK) // a round second, worst case for trailing-zero stripping
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, AggregatorReady, CheckReady(client, srv.URL))

	if !fixedWidthTimestamp.MatchString(res.Timestamp) {
		t.Fatalf("expected a fixed-width 9-digit fractional-second timestamp, got %q", res.Timestamp)
	}
}

func decodeResults(t *testing.T, buf *strings.Builder) []Record {
	t.Helper()
	var out []Record
	for _, line := range strings.Split(strings.TrimSpace(buf.String()), "\n") {
		if line == "" {
			continue
		}
		var r Record
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
	res := runProbe(context.Background(), &buf, &mu, AggregatorHealth, CheckHealth(client, srv.URL))

	if !res.Success {
		t.Fatalf("expected success, got error=%q", res.Error)
	}
	if res.HTTPCode != http.StatusOK {
		t.Fatalf("expected httpCode 200, got %d", res.HTTPCode)
	}
}

func TestCheckHealth_ProblemWithHTTP200Fails(t *testing.T) {
	// The aggregator returns HTTP 200 even when its own reported status is "PROBLEM" — the status
	// code alone must never be treated as success. The error must name the failing component and
	// its status (v6.15.0's cached adapters-access indicator is exactly this shape) so a recorded
	// failure is actionable instead of just "health status=PROBLEM".
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"PROBLEM","components":{"adaptersAccessIndicator":{"status":"PROBLEM"},"other":{"status":"UP"}}}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, AggregatorHealth, CheckHealth(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure for status=PROBLEM despite HTTP 200")
	}
	if res.HTTPCode != http.StatusOK {
		t.Fatalf("expected httpCode 200 recorded even on a failed probe, got %d", res.HTTPCode)
	}
	if !strings.Contains(res.Error, "adaptersAccessIndicator:PROBLEM") {
		t.Fatalf("expected the error to name the failing component and its status, got: %q", res.Error)
	}
	if strings.Contains(res.Error, "other:") {
		t.Fatalf("expected only the failing component to be named, not the healthy one, got: %q", res.Error)
	}
}

func TestCheckHealth_MalformedResponseFails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{not valid json`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, AggregatorHealth, CheckHealth(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure for a malformed health response")
	}
	if res.Error == "" {
		t.Fatalf("expected a non-empty error message")
	}
}

func TestCheckHealth_Non200Fails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, AggregatorHealth, CheckHealth(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure for HTTP 503")
	}
	if res.HTTPCode != http.StatusServiceUnavailable {
		t.Fatalf("expected httpCode 503, got %d", res.HTTPCode)
	}
}

func TestCheckHealth_NeverLogsComponentDetailsOrBody(t *testing.T) {
	const leakedSecret = "s3cr3t-connection-detail"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"PROBLEM","components":{"adaptersAccessIndicator":{"status":"PROBLEM","details":{"url":"jdbc:postgresql://host/db","password":"` +
			leakedSecret + `"}}}}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, AggregatorHealth, CheckHealth(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure for status=PROBLEM")
	}
	output := buf.String()
	if strings.Contains(output, leakedSecret) {
		t.Fatalf("probe output must never contain a component's details, got: %s", output)
	}
	if strings.Contains(output, "jdbc:postgresql") {
		t.Fatalf("probe output must never contain the raw health response body, got: %s", output)
	}
	if !strings.Contains(res.Error, "adaptersAccessIndicator:PROBLEM") {
		t.Fatalf("expected the error to still name the failing component, got: %q", res.Error)
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
	res := runProbe(context.Background(), &buf, &mu, AggregatorReady, CheckReady(client, srv.URL))

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
	res := runProbe(ctx, &buf, &mu, AggregatorReady, CheckReady(client, srv.URL))

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
	res := runProbe(context.Background(), &buf, &mu, AggregatorReady, CheckReady(client, "http://"+addr))

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
	runLoop(ctx, &buf, &mu, AggregatorReady, CheckReady(client, srv.URL), 10*time.Millisecond, time.Second)

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

func TestCheckSamplePing_UnexpectedPayloadFails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok","result":0}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, SamplePostgresPing, CheckSamplePing(client, srv.URL))

	if res.Success {
		t.Fatalf("expected failure when result != 1")
	}

	results := decodeResults(t, &buf)
	if len(results) != 1 || results[0].Probe != SamplePostgresPing {
		t.Fatalf("expected exactly one sample-postgres-ping result line, got %v", results)
	}
}

func TestCheckSamplePing_ExtraResponseFieldsAreIgnored(t *testing.T) {
	// /postgres/ping also returns "url", "username", and "role" — none of that should ever
	// surface, and it must not break decoding of the two fields this probe actually checks.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok","url":"jdbc:postgresql://host/db","username":"app","role":"master","result":1}`))
	}))
	defer srv.Close()

	var buf strings.Builder
	var mu sync.Mutex
	client := &http.Client{Timeout: time.Second}
	res := runProbe(context.Background(), &buf, &mu, SamplePostgresPing, CheckSamplePing(client, srv.URL))

	if !res.Success {
		t.Fatalf("expected success, got error=%q", res.Error)
	}
	if strings.Contains(buf.String(), "jdbc:postgresql") {
		t.Fatalf("probe output must never contain the raw ping response body, got: %s", buf.String())
	}
}

// --- RunPreflight (pre-transition prerequisite) ---

func healthyPodServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}))
}

func TestRunPreflight_BothPodsHealthyPasses(t *testing.T) {
	pod1 := healthyPodServer(t)
	defer pod1.Close()
	pod2 := healthyPodServer(t)
	defer pod2.Close()

	client := &http.Client{Timeout: time.Second}
	targets := []PreflightTarget{
		{Name: "pod-1", Check: CheckHealth(client, pod1.URL)},
		{Name: "pod-2", Check: CheckHealth(client, pod2.URL)},
	}

	err := RunPreflight(context.Background(), targets, time.Second, time.Millisecond, 3, time.Second)
	if err != nil {
		t.Fatalf("expected both healthy pods to pass, got: %v", err)
	}
}

func TestRunPreflight_OnePodNeverHealthyTimesOutNamingThePod(t *testing.T) {
	pod1 := healthyPodServer(t)
	defer pod1.Close()
	pod2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"PROBLEM","components":{"adaptersAccessIndicator":{"status":"PROBLEM"}}}`))
	}))
	defer pod2.Close()

	client := &http.Client{Timeout: time.Second}
	targets := []PreflightTarget{
		{Name: "pod-1", Check: CheckHealth(client, pod1.URL)},
		{Name: "pod-2-bad", Check: CheckHealth(client, pod2.URL)},
	}

	err := RunPreflight(context.Background(), targets, time.Second, time.Millisecond, 3, 20*time.Millisecond)
	if err == nil {
		t.Fatalf("expected a timeout error when one pod never becomes healthy")
	}
	if !strings.Contains(err.Error(), "pod-2-bad") || !strings.Contains(err.Error(), "adaptersAccessIndicator:PROBLEM") {
		t.Fatalf("expected the error to name the failing pod and component, got: %v", err)
	}
}

func TestRunPreflight_ResetsConsecutiveCountOnFailure(t *testing.T) {
	// Fails on the 3rd call, then recovers — with stableConsecutive=3, this must never pass on the
	// strength of the first two calls alone; it needs 3 in a row after the failure.
	var calls int
	pod := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls == 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}))
	defer pod.Close()

	client := &http.Client{Timeout: time.Second}
	targets := []PreflightTarget{{Name: "pod-1", Check: CheckHealth(client, pod.URL)}}

	err := RunPreflight(context.Background(), targets, time.Second, time.Millisecond, 3, 2*time.Second)
	if err != nil {
		t.Fatalf("expected eventual success once the pod recovers, got: %v", err)
	}
	if calls < 6 {
		t.Fatalf("expected at least 6 calls (2 ok + 1 fail + 3 ok to reach 3 consecutive), got %d", calls)
	}
}

func TestRunPreflight_UnreachablePodFailsLikeAReplacedPod(t *testing.T) {
	// A pod pinned by IP that stops answering — because it was replaced — must fail the same way
	// any other unreachable pod does; there is no separate "pod changed" code path to test
	// independently.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve a local port: %v", err)
	}
	addr := l.Addr().String()
	_ = l.Close()

	client := &http.Client{Timeout: 50 * time.Millisecond}
	targets := []PreflightTarget{{Name: "pod-gone", Check: CheckHealth(client, "http://"+addr)}}

	waitErr := RunPreflight(context.Background(), targets, 50*time.Millisecond, time.Millisecond, 3, 20*time.Millisecond)
	if waitErr == nil {
		t.Fatalf("expected an error when the pinned pod is unreachable")
	}
	if !strings.Contains(waitErr.Error(), "pod-gone") {
		t.Fatalf("expected the error to name the unreachable pod, got: %v", waitErr)
	}
}

func TestRunPreflight_DoesNotPassAfterOverallTimeout(t *testing.T) {
	pod := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(30 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	}))
	defer pod.Close()

	client := &http.Client{Timeout: time.Second}
	targets := []PreflightTarget{{Name: "slow-pod", Check: CheckHealth(client, pod.URL)}}

	err := RunPreflight(context.Background(), targets, time.Second, time.Millisecond, 1, 10*time.Millisecond)
	if err == nil {
		t.Fatal("expected the overall timeout to reject a late healthy response")
	}
}
