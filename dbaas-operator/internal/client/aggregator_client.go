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
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/go-resty/resty/v2"
	"github.com/netcracker/qubership-core-lib-go-error-handling/v3/tmf"
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
	rc    *resty.Client
	creds atomic.Pointer[credentials]
}

// NewAggregatorClient creates a new AggregatorClient.
//
//   - baseURL  — base URL of dbaas-aggregator without a trailing slash
//     (e.g. "http://dbaas-aggregator:8080").
//   - username / password — credentials for HTTP Basic authentication.
//     The account must have the DB_CLIENT role in dbaas-aggregator.
func NewAggregatorClient(baseURL, username, password string) *AggregatorClient {
	c := &AggregatorClient{}
	c.creds.Store(&credentials{username: username, password: password})

	c.rc = resty.New().
		SetBaseURL(baseURL).
		SetTimeout(defaultTimeout).
		SetHeader("Accept", "application/json").
		OnBeforeRequest(func(_ *resty.Client, r *resty.Request) error {
			cr := c.creds.Load()
			r.SetBasicAuth(cr.username, cr.password)
			return nil
		})

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
//   - response.TrackingID != "" → operation is asynchronous (HTTP 202 from the
//     aggregator); call GetOperationStatus to poll for completion.
//   - response.TrackingID == "" → operation completed synchronously (HTTP 200);
//     inspect response.Conditions for the outcome.
//   - error (*AggregatorError) → non-2xx response; IsSpecRejection() distinguishes
//     a permanent spec error (400/403/409/410/422) from a transient failure.
func (c *AggregatorClient) ApplyConfig(ctx context.Context, payload *DeclarativePayload) (*DeclarativeResponse, error) {
	resp, err := c.rc.R().
		SetContext(ctx).
		SetBody(payload).
		Post("/api/declarations/v1/apply")
	if err != nil {
		return nil, err
	}

	if resp.StatusCode() != http.StatusOK && resp.StatusCode() != http.StatusAccepted {
		return nil, newAggregatorError(resp)
	}

	var result DeclarativeResponse
	if len(resp.Body()) > 0 {
		if err := json.Unmarshal(resp.Body(), &result); err != nil {
			return nil, fmt.Errorf("decode apply response: %w", err)
		}
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
	var result DeclarativeResponse

	resp, err := c.rc.R().
		SetContext(ctx).
		SetResult(&result).
		Get(fmt.Sprintf("/api/declarations/v1/operation/%s/status", trackingID))
	if err != nil {
		return nil, err
	}

	if resp.StatusCode() != http.StatusOK {
		return nil, newAggregatorError(resp)
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
	resp, err := c.rc.R().
		SetContext(ctx).
		SetBody(req).
		Put(fmt.Sprintf("/api/v3/dbaas/%s/databases/registration/externally_manageable", namespace))
	if err != nil {
		return err
	}

	if resp.StatusCode() != http.StatusOK && resp.StatusCode() != http.StatusCreated {
		return newAggregatorError(resp)
	}

	return nil
}

func newAggregatorError(resp *resty.Response) *AggregatorError {
	aggErr := &AggregatorError{
		StatusCode: resp.StatusCode(),
		Body:       string(resp.Body()),
	}

	var tmfResp tmf.Response
	if json.Unmarshal(resp.Body(), &tmfResp) == nil && tmfResp.Message != "" {
		aggErr.TmfMessage = tmfResp.Message
	}

	return aggErr
}
