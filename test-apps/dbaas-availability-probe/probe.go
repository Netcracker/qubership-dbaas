// Command dbaas-availability-probe continuously exercises dbaas-aggregator from inside the target
// Kind cluster while its Helm release is upgraded or downgraded, so an availability regression during
// the transition shows up as a recorded probe failure instead of a silent gap. It talks to in-cluster
// Service DNS only — never through kubectl port-forward, whose own interruptions would otherwise look
// indistinguishable from a real DBaaS outage.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"
)

// timestampLayout always renders a fixed-width 9-digit fractional-second field. evaluate-probes.sh
// orders samples against the transition-window boundary with plain string comparison (jq's `<`),
// which is only chronologically correct when every compared timestamp has the same width — Go's
// time.RFC3339Nano strips trailing zero digits (".5Z" instead of ".500000000Z"), which on a CI
// runner's clock happens often enough in practice to misclassify a sample that landed a few
// nanoseconds on either side of the boundary. Format with explicit zeros instead of RFC3339Nano's
// nines so no digit is ever stripped, matching the fixed-width boundary timestamps written by
// transition-aggregator.sh's `date -u +%Y-%m-%dT%H:%M:%S.%NZ`.
const timestampLayout = "2006-01-02T15:04:05.000000000Z07:00"

// probeResult is the single JSON object each probe cycle writes to stdout — one line per probe kind
// per cycle, so the evaluator can reconstruct the full timeline (baseline / transition / post) from
// captured pod logs alone.
type probeResult struct {
	Timestamp string `json:"timestamp"`
	Probe     string `json:"probe"`
	Success   bool   `json:"success"`
	HTTPCode  int    `json:"httpCode"`
	LatencyMs int64  `json:"latencyMs"`
	Error     string `json:"error"`
}

// checkFunc performs one probe attempt and reports the HTTP status observed (0 if no response was
// received at all, e.g. a timeout or connection refusal) and a non-nil error on any failure. It must
// never panic and must never include a raw response body in the returned error — only status codes,
// decoded scalar fields, and Go's own network-error text, none of which can carry a credential or a
// classifier's connectionProperties.
type checkFunc func(ctx context.Context) (httpCode int, err error)

// runProbe times fn, builds the result line, and writes it to out under mu. Any error from fn (network
// failure, non-200 status, JSON decode failure, an unexpected decoded value) is recorded as a failed
// probe — it never propagates and never stops the caller from scheduling the next cycle.
func runProbe(ctx context.Context, out io.Writer, mu *sync.Mutex, name string, fn checkFunc) probeResult {
	start := time.Now()
	httpCode, err := fn(ctx)
	res := probeResult{
		Timestamp: time.Now().UTC().Format(timestampLayout),
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

func writeResult(out io.Writer, mu *sync.Mutex, res probeResult) {
	line, err := json.Marshal(res)
	if err != nil {
		// probeResult always marshals; guard anyway rather than ever panicking the probe loop.
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

// checkReady requires HTTP 200 from GET {aggregatorURL}/probes/ready. No body is decoded — readiness
// carries no payload worth inspecting.
func checkReady(client *http.Client, aggregatorURL string) checkFunc {
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

type healthResponse struct {
	Status string `json:"status"`
}

// checkHealth requires HTTP 200 AND a decoded body with status == "UP" from GET {aggregatorURL}/health.
// The aggregator also returns HTTP 200 for a "PROBLEM" status, so the status code alone proves nothing
// — the body must be decoded and checked on every cycle.
func checkHealth(client *http.Client, aggregatorURL string) checkFunc {
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
			return resp.StatusCode, fmt.Errorf("health status=%s", h.Status)
		}
		return resp.StatusCode, nil
	}
}

// maxDecodeBytes bounds every response body read by this probe. It is generous for the small JSON
// payloads dbaas-aggregator and the sample service return, and exists only to stop a misbehaving
// endpoint from making the probe buffer an unbounded body.
const maxDecodeBytes = 1 << 20 // 1 MiB

// classifierIdentity is the subset of the aggregator's Database entity this probe ever decodes. The
// real response also carries connectionProperties (host, credentials) and resources; those fields have
// no matching tag here, so encoding/json drops them during Decode and this process never holds them in
// any variable that could reach a log line.
type classifierIdentity struct {
	ID         string         `json:"id"`
	Name       string         `json:"name"`
	Namespace  string         `json:"namespace"`
	Type       string         `json:"type"`
	Classifier map[string]any `json:"classifier"`
}

// checkClassifier requires HTTP 200 from POST
// {aggregatorURL}/api/v3/dbaas/{namespace}/databases/get-by-classifier/{type}, authenticated with HTTP Basic as
// a DB_CLIENT-role user. It decodes only the nonsensitive identity fields above; the raw response body
// is parsed in memory and discarded — it is never written to a log, an error string, or stdout.
func checkClassifier(client *http.Client, aggregatorURL, namespace, dbType, microserviceName, scope, username, password string) checkFunc {
	url := aggregatorURL + "/api/v3/dbaas/" + namespace + "/databases/get-by-classifier/" + dbType
	body, _ := json.Marshal(map[string]any{
		"classifier": map[string]any{
			"microserviceName": microserviceName,
			"scope":            scope,
			"namespace":        namespace,
		},
		"originService": microserviceName,
		"userRole":      "admin",
	})
	return func(ctx context.Context) (int, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
		if err != nil {
			return 0, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.SetBasicAuth(username, password)
		resp, err := client.Do(req)
		if err != nil {
			return 0, err
		}
		defer drainAndClose(resp)
		if resp.StatusCode != http.StatusOK {
			return resp.StatusCode, fmt.Errorf("unexpected status %d", resp.StatusCode)
		}
		raw, err := io.ReadAll(io.LimitReader(resp.Body, maxDecodeBytes))
		if err != nil {
			return resp.StatusCode, fmt.Errorf("read classifier response: %w", err)
		}
		var identity classifierIdentity
		if err := json.Unmarshal(raw, &identity); err != nil {
			return resp.StatusCode, fmt.Errorf("decode classifier response: %w", err)
		}
		if identity.Namespace != namespace {
			return resp.StatusCode, fmt.Errorf("unexpected namespace in classifier response")
		}
		return resp.StatusCode, nil
	}
}

type pingResponse struct {
	Status string `json:"status"`
	Result int    `json:"result"`
}

// checkSamplePing requires HTTP 200 AND a decoded body with status == "ok" and result == 1 from GET
// {sampleServiceURL}/postgres/ping. The sample service resolves its DBaaS connection and runs SELECT 1
// on every call, so a passing ping proves the DBaaS lookup, the adapter-provided connection
// properties, and real PostgreSQL access are all working end to end.
func checkSamplePing(client *http.Client, sampleServiceURL string) checkFunc {
	url := sampleServiceURL + "/postgres/ping"
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
		var p pingResponse
		if err := json.NewDecoder(io.LimitReader(resp.Body, maxDecodeBytes)).Decode(&p); err != nil {
			return resp.StatusCode, fmt.Errorf("decode ping response: %w", err)
		}
		if p.Status != "ok" || p.Result != 1 {
			return resp.StatusCode, fmt.Errorf("unexpected ping payload status=%s result=%d", p.Status, p.Result)
		}
		return resp.StatusCode, nil
	}
}
