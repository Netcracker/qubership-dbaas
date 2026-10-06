// Package probe implements aggregator availability checks and result evaluation.
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

// TimestampLayout is the fixed-width format used by probe records and measurement boundaries.
const TimestampLayout = "2006-01-02T15:04:05.000000000Z07:00"

// Record is one JSONL probe result.
type Record struct {
	Timestamp string `json:"timestamp"`
	Probe     string `json:"probe"`
	Success   bool   `json:"success"`
	HTTPCode  int    `json:"httpCode"`
	LatencyMs int64  `json:"latencyMs"`
	Error     string `json:"error"`
}

// CheckFunc returns status 0 when no HTTP response is received.
type CheckFunc func(ctx context.Context) (httpCode int, err error)

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

// CheckReady requires HTTP 200 from the aggregator readiness endpoint.
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

// healthComponent omits details so connection data cannot reach probe output.
type healthComponent struct {
	Status string `json:"status"`
}

type healthResponse struct {
	Status     string                     `json:"status"`
	Components map[string]healthComponent `json:"components"`
}

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

// CheckHealth requires HTTP 200 and a response status of UP.
// Failure messages include component statuses but exclude component details.
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
