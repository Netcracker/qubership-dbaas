// Package probe implements the in-cluster availability checks the DBaaS transition test runs
// against the aggregator, the continuous and preflight run loops around them, and the evaluator
// that turns a captured run into a pass/fail verdict.
package probe

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// TimestampLayout keeps probe timestamps in the same fixed-width format as the measurement
// boundaries recorded by the test steps.
const TimestampLayout = "2006-01-02T15:04:05.000000000Z07:00"

// Record is the single JSON object each probe cycle writes to stdout — one line per probe kind
// per cycle, so the evaluator can reconstruct the full timeline (baseline / transition / post)
// from captured pod logs alone.
type Record struct {
	Timestamp string `json:"timestamp"`
	Probe     string `json:"probe"`
	Success   bool   `json:"success"`
	HTTPCode  int    `json:"httpCode"`
	LatencyMs int64  `json:"latencyMs"`
	Error     string `json:"error"`
}

// CheckFunc performs one probe attempt and reports the HTTP status observed (0 if no response was
// received at all, e.g. a timeout or connection refusal) and a non-nil error on any failure. It
// must never panic and must never include a raw response body in the returned error — only status
// codes, decoded scalar fields, and Go's own network-error text, none of which can carry
// credentials.
type CheckFunc func(ctx context.Context) (httpCode int, err error)

// runProbe times fn, builds the result line, and writes it to out under mu. Any error from fn
// (network failure, non-200 status, JSON decode failure, an unexpected decoded value) is recorded
// as a failed probe — it never propagates and never stops the caller from scheduling the next
// cycle.
func runProbe(ctx context.Context, out io.Writer, mu *sync.Mutex, name string, fn CheckFunc) Record {
	start := time.Now()
	httpCode, err := fn(ctx)
	res := Record{
		Timestamp: time.Now().UTC().Format(TimestampLayout),
		Probe:     name,
		Success:   err == nil,
		HTTPCode:  httpCode,
		LatencyMs: time.Since(start).Milliseconds(),
	}
	if err != nil {
		res.Error = err.Error()
	}
	writeResult(out, mu, res)
	return res
}

func writeResult(out io.Writer, mu *sync.Mutex, res Record) {
	line, err := json.Marshal(res)
	if err != nil {
		// Record always marshals; guard anyway rather than ever panicking the probe loop.
		return
	}
	line = append(line, '\n')
	if mu != nil {
		mu.Lock()
		defer mu.Unlock()
	}
	_, _ = out.Write(line)
}

func drainAndClose(resp *http.Response) {
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
}

// CheckReady requires HTTP 200 from GET {aggregatorURL}/probes/ready. No body is decoded —
// readiness carries no payload worth inspecting.
func CheckReady(client *http.Client, aggregatorURL string) CheckFunc {
	url := aggregatorURL + "/probes/ready"
	return func(ctx context.Context) (int, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return 0, err
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, err
		}
		defer drainAndClose(resp)
		if resp.StatusCode != http.StatusOK {
			return resp.StatusCode, fmt.Errorf("unexpected status %d", resp.StatusCode)
		}
		return resp.StatusCode, nil
	}
}

// healthComponent is the subset of the aggregator's per-component health entry this probe ever
// decodes. The real entry also carries a "details" field (arbitrary key/value diagnostic data,
// which for the adapter-access indicator can include connection-related information) — that field
// has no matching tag here, so encoding/json drops it during Decode and this process never holds
// it in any variable that could reach a log line.
type healthComponent struct {
	Status string `json:"status"`
}

// healthResponse mirrors AggregatedHealthResponse's JSON shape: a top-level status plus a
// "components" map keyed by component name (e.g. "adaptersAccessIndicator"), each with its own
// status.
type healthResponse struct {
	Status     string                     `json:"status"`
	Components map[string]healthComponent `json:"components"`
}

// failingComponents returns "name:status" for every component not reporting UP, sorted for a
// deterministic, testable error message. Never includes a component's details.
func (h healthResponse) failingComponents() []string {
	var failing []string
	for name, c := range h.Components {
		if c.Status != "UP" {
			failing = append(failing, name+":"+c.Status)
		}
	}
	sort.Strings(failing)
	return failing
}

// CheckHealth requires HTTP 200 AND a decoded body with status == "UP" from GET
// {aggregatorURL}/health. The aggregator also returns HTTP 200 for a "PROBLEM" status, so the
// status code alone proves nothing — the body must be decoded and checked on every cycle. On a
// non-UP status the returned error names the failing component(s) and their status (e.g.
// "adaptersAccessIndicator:PROBLEM") so a recorded failure is actionable — but never a component's
// details or the raw response body, either of which can carry connection information.
func CheckHealth(client *http.Client, aggregatorURL string) CheckFunc {
	url := aggregatorURL + "/health"
	return func(ctx context.Context) (int, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return 0, err
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, err
		}
		defer drainAndClose(resp)
		if resp.StatusCode != http.StatusOK {
			return resp.StatusCode, fmt.Errorf("unexpected status %d", resp.StatusCode)
		}
		var h healthResponse
		if err := json.NewDecoder(io.LimitReader(resp.Body, maxDecodeBytes)).Decode(&h); err != nil {
			return resp.StatusCode, fmt.Errorf("decode health response: %w", err)
		}
		if h.Status != "UP" {
			if failing := h.failingComponents(); len(failing) > 0 {
				return resp.StatusCode, fmt.Errorf("health status=%s components=%s", h.Status, strings.Join(failing, ","))
			}
			return resp.StatusCode, fmt.Errorf("health status=%s", h.Status)
		}
		return resp.StatusCode, nil
	}
}
