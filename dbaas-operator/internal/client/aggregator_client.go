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

// Package client provides an HTTP client for the dbaas-aggregator REST API.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync/atomic"
	"time"
)

const defaultTimeout = 30 * time.Second

// credentials holds the Basic Auth pair as an immutable value.
// The pair is always replaced atomically — never partially updated.
type credentials struct {
	username, password string
}

// AggregatorClient is an HTTP client for the dbaas-aggregator REST API.
// It is safe for concurrent use, including concurrent credential updates.
type AggregatorClient struct {
	baseURL    string
	httpClient *http.Client
	creds      atomic.Pointer[credentials]
}

// NewAggregatorClient creates a new AggregatorClient.
//
//   - baseURL  — base URL of dbaas-aggregator without a trailing slash
//     (e.g. "http://dbaas-aggregator:8080").
//   - username / password — credentials for HTTP Basic authentication.
//     The account must have the DB_CLIENT role in dbaas-aggregator.
func NewAggregatorClient(baseURL, username, password string) *AggregatorClient {
	c := &AggregatorClient{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: defaultTimeout,
		},
	}
	c.creds.Store(&credentials{username: username, password: password})
	return c
}

// SetCredentials atomically replaces the Basic Auth credentials used for all
// subsequent requests. Safe to call concurrently with in-flight requests.
func (c *AggregatorClient) SetCredentials(username, password string) {
	c.creds.Store(&credentials{username: username, password: password})
}

// ApplyConfig posts a declarative payload to POST /api/declarations/v1/apply.
//
// The caller constructs the payload with the appropriate kind/subKind/spec for
// the resource (DatabaseDeclaration or DbPolicy).
//
// Return semantics:
//   - response.TrackingId != "" → operation is asynchronous (HTTP 202 from the
//     aggregator); call GetOperationStatus to poll for completion.
//   - response.TrackingId == "" → operation completed synchronously (HTTP 200);
//     inspect response.Conditions for the outcome.
//   - error (*AggregatorError) → non-2xx response; IsClientError() distinguishes
//     a permanent config error from a transient failure.
func (c *AggregatorClient) ApplyConfig(ctx context.Context, payload *DeclarativePayload) (*DeclarativeResponse, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("marshal DeclarativePayload: %w", err)
	}

	url := c.baseURL + "/api/declarations/v1/apply"
	resp, err := c.do(ctx, http.MethodPost, url, body)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	rawBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read ApplyConfig response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusAccepted {
		return nil, &AggregatorError{StatusCode: resp.StatusCode, Body: string(rawBody)}
	}

	var result DeclarativeResponse
	if err := json.Unmarshal(rawBody, &result); err != nil {
		return nil, fmt.Errorf("decode ApplyConfig response: %w", err)
	}
	return &result, nil
}

// GetOperationStatus polls the status of an asynchronous operation.
//
// The trackingId is obtained from a previous ApplyConfig call.  The caller
// should keep calling this method while the returned Status is TaskStateInProgress.
//
// Returns *AggregatorError on non-2xx.
func (c *AggregatorClient) GetOperationStatus(ctx context.Context, trackingID string) (*DeclarativeResponse, error) {
	url := fmt.Sprintf("%s/api/declarations/v1/operation/%s/status", c.baseURL, trackingID)
	resp, err := c.do(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	rawBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read GetOperationStatus response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, &AggregatorError{StatusCode: resp.StatusCode, Body: string(rawBody)}
	}

	var result DeclarativeResponse
	if err := json.Unmarshal(rawBody, &result); err != nil {
		return nil, fmt.Errorf("decode GetOperationStatus response: %w", err)
	}
	return &result, nil
}

// RegisterExternalDatabase sends a PUT request to register an externally managed
// database at PUT /api/v3/dbaas/{namespace}/databases/registration/externally_manageable.
//
// The namespace argument is used in the URL path and must match
// req.Classifier["namespace"].
//
// The call is synchronous; no polling is needed.
// Returns *AggregatorError on non-2xx.
func (c *AggregatorClient) RegisterExternalDatabase(ctx context.Context, namespace string, req *ExternalDatabaseRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("marshal ExternalDatabaseRequest: %w", err)
	}

	url := fmt.Sprintf(
		"%s/api/v3/dbaas/%s/databases/registration/externally_manageable",
		c.baseURL, namespace,
	)
	resp, err := c.do(ctx, http.MethodPut, url, body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	rawBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read RegisterExternalDatabase response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return &AggregatorError{StatusCode: resp.StatusCode, Body: string(rawBody)}
	}
	return nil
}

// do executes an HTTP request with Basic auth and JSON content/accept headers.
func (c *AggregatorClient) do(ctx context.Context, method, url string, body []byte) (*http.Response, error) {
	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("create HTTP request %s %s: %w", method, url, err)
	}

	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	cr := c.creds.Load()
	req.SetBasicAuth(cr.username, cr.password)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("execute %s %s: %w", method, url, err)
	}
	return resp, nil
}
