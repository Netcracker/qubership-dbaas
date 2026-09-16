package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// verify.go implements the two one-shot Job modes (PROBE_MODE=verify / verify-post) that bracket the
// continuous probe: a pre-transition functional check that seeds a recognizable record, and a
// post-transition check that reads it back and checks the database identity. Both run as a
// Kubernetes Job using this same image, so no extra container image (and no assumption about what
// tools happen to be installed in it) is needed to talk to the sample service from inside the cluster.

type postgresItem struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type itemsResponse struct {
	Items []postgresItem `json:"items"`
}

type itemResponse struct {
	Item postgresItem `json:"item"`
}

type connectionPropertiesResponse struct {
	URL string `json:"url"`
}

// fixtureFingerprint is the nonsensitive database identity recorded before the transition and checked
// again after it. url is already password-redacted by the sample service (sanitizeURL); nothing here
// can carry a credential.
type fixtureFingerprint struct {
	ItemID   int64  `json:"itemId"`
	ItemName string `json:"itemName"`
	URL      string `json:"url"`
}

func fail(errOut io.Writer, format string, args ...any) {
	fmt.Fprintf(errOut, "FIXTURE_ERROR: "+format+"\n", args...)
	os.Exit(1)
}

// waitReadyOnce is a single readiness attempt: the aggregator readiness probe, then — only once that
// succeeds — the sample service's /postgres/ping. A ping success proves the sample can actually reach
// PostgreSQL through the aggregator/adapter, not just that its process answers HTTP; checkSamplePing
// already requires the decoded body to be {"status":"ok","result":1}, not HTTP 200 alone. Short-circuits
// on the aggregator check so a down aggregator is reported as that, not conflated with a ping failure.
func waitReadyOnce(ctx context.Context, ready, ping checkFunc) error {
	if _, err := ready(ctx); err != nil {
		return fmt.Errorf("aggregator not ready: %w", err)
	}
	if _, err := ping(ctx); err != nil {
		return fmt.Errorf("sample service postgres ping not ready: %w", err)
	}
	return nil
}

// waitReadyRetry retries waitReadyOnce up to maxAttempts times, sleeping sleepBetween in between, and
// returns the last attempt's error if none of them succeeded. Every error wraps only the checkFuncs'
// own error text (status codes, decoded scalar fields, Go's network-error text) — never a response
// body or a credential.
func waitReadyRetry(cfg config, ready, ping checkFunc, maxAttempts int, sleepBetween time.Duration) error {
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), cfg.requestTimeout)
		lastErr = waitReadyOnce(ctx, ready, ping)
		cancel()
		if lastErr == nil {
			return nil
		}
		if attempt < maxAttempts {
			time.Sleep(sleepBetween)
		}
	}
	return lastErr
}

// waitReady blocks (with bounded retries) until both the aggregator and the sample service are ready,
// or gives up and fails the Job. The workflow already waits for the Deployments' rollout status before
// scheduling this Job, so this is a short defensive retry, not the primary readiness gate. Used both
// for the initial fixture setup and after the intentional post-transition sample-service restart.
func waitReady(cfg config, errOut io.Writer) {
	client := &http.Client{Timeout: cfg.requestTimeout}
	ready := checkReady(client, cfg.aggregatorURL)
	ping := checkSamplePing(client, cfg.sampleServiceURL)
	const maxAttempts = 20
	if err := waitReadyRetry(cfg, ready, ping, maxAttempts, 3*time.Second); err != nil {
		fail(errOut, "fixture not ready after %d attempts: %v", maxAttempts, err)
	}
}

func sampleRequest(ctx context.Context, client *http.Client, method, url string, body any) (*http.Response, error) {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return client.Do(req)
}

func decodeJSON[T any](resp *http.Response) (T, error) {
	var out T
	defer drainAndClose(resp)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return out, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	err := json.NewDecoder(io.LimitReader(resp.Body, maxDecodeBytes)).Decode(&out)
	return out, err
}

// runVerify seeds one recognizable record through the sample service and records its nonsensitive
// fingerprint (id, name, sanitized connection URL) as a single FIXTURE_FINGERPRINT: <json> line on
// stdout, so the calling workflow step can capture it from `kubectl logs` and pass it to runVerifyPost
// after the transition. Any failure is reported as FIXTURE_ERROR: <message> on stderr with exit 1 —
// never with the response body, which the connection-properties call in particular could otherwise
// leak indirectly if it were echoed raw.
func runVerify(cfg config, out, errOut io.Writer) {
	waitReady(cfg, errOut)
	client := &http.Client{Timeout: cfg.requestTimeout}
	ctx := context.Background()

	createCtx, cancel := context.WithTimeout(ctx, cfg.requestTimeout)
	resp, err := sampleRequest(createCtx, client, http.MethodPost, cfg.sampleServiceURL+"/postgres/items", map[string]string{"name": cfg.itemMarker})
	if err != nil {
		cancel()
		fail(errOut, "create fixture item: %v", err)
	}
	created, err := decodeJSON[itemResponse](resp)
	cancel()
	if err != nil {
		fail(errOut, "decode created fixture item: %v", err)
	}

	propsCtx, cancel := context.WithTimeout(ctx, cfg.requestTimeout)
	resp, err = sampleRequest(propsCtx, client, http.MethodGet, cfg.sampleServiceURL+"/postgres/connection-properties", nil)
	if err != nil {
		cancel()
		fail(errOut, "fetch connection properties: %v", err)
	}
	props, err := decodeJSON[connectionPropertiesResponse](resp)
	cancel()
	if err != nil {
		fail(errOut, "decode connection properties: %v", err)
	}

	fp := fixtureFingerprint{
		ItemID:   created.Item.ID,
		ItemName: created.Item.Name,
		URL:      props.URL,
	}
	line, err := json.Marshal(fp)
	if err != nil {
		fail(errOut, "marshal fixture fingerprint: %v", err)
	}
	fmt.Fprintf(out, "FIXTURE_FINGERPRINT: %s\n", line)
}

// runVerifyPost reads the record runVerify created and checks that the sanitized connection URL did
// not change. FINGERPRINT_JSON holds the FIXTURE_FINGERPRINT payload from the runVerify Job's log.
func runVerifyPost(cfg config, out, errOut io.Writer) {
	waitReady(cfg, errOut)

	raw := os.Getenv("FINGERPRINT_JSON")
	if strings.TrimSpace(raw) == "" {
		fail(errOut, "FINGERPRINT_JSON is not set")
	}
	var before fixtureFingerprint
	if err := json.Unmarshal([]byte(raw), &before); err != nil {
		fail(errOut, "decode FINGERPRINT_JSON: %v", err)
	}

	client := &http.Client{Timeout: cfg.requestTimeout}
	ctx := context.Background()

	// READ: the pre-transition record must still be visible.
	listCtx, cancel := context.WithTimeout(ctx, cfg.requestTimeout)
	resp, err := sampleRequest(listCtx, client, http.MethodGet, cfg.sampleServiceURL+"/postgres/items", nil)
	if err != nil {
		cancel()
		fail(errOut, "list items: %v", err)
	}
	listed, err := decodeJSON[itemsResponse](resp)
	cancel()
	if err != nil {
		fail(errOut, "decode item list: %v", err)
	}
	found := false
	for _, it := range listed.Items {
		if it.ID == before.ItemID && it.Name == before.ItemName {
			found = true
			break
		}
	}
	if !found {
		fail(errOut, "pre-transition record id=%d not found after transition", before.ItemID)
	}

	// Confirm the logical database identity (sanitized connection URL) is unchanged.
	propsCtx, cancel := context.WithTimeout(ctx, cfg.requestTimeout)
	resp, err = sampleRequest(propsCtx, client, http.MethodGet, cfg.sampleServiceURL+"/postgres/connection-properties", nil)
	if err != nil {
		cancel()
		fail(errOut, "fetch post-transition connection properties: %v", err)
	}
	after, err := decodeJSON[connectionPropertiesResponse](resp)
	cancel()
	if err != nil {
		fail(errOut, "decode post-transition connection properties: %v", err)
	}
	if after.URL != before.URL {
		fail(errOut, "logical database identity changed: before=%q after=%q", before.URL, after.URL)
	}

	fmt.Fprintln(out, "FIXTURE_POST_OK")
}
