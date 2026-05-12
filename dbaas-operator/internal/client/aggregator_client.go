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
	"time"

	"github.com/go-resty/resty/v2"
	"github.com/netcracker/qubership-core-lib-go-error-handling/v3/tmf"
	"github.com/netcracker/qubership-core-lib-go/v3/security/tokensource"
)

const defaultTimeout = 30 * time.Second

// AggregatorClient is an HTTP client for the dbaas-aggregator REST API.
// It authenticates using a Kubernetes projected service account token with
// audience "dbaas", fetched fresh on every request via the tokensource library.
// It is safe for concurrent use.
type AggregatorClient struct {
	rc       *resty.Client
	getToken func(ctx context.Context) (string, error)
}

// NewAggregatorClient creates a new AggregatorClient.
//
//   - baseURL — base URL of dbaas-aggregator without a trailing slash
//     (e.g. "http://dbaas-aggregator:8080").
//
// The client fetches a fresh Kubernetes service account token with audience
// "dbaas" on every outgoing request from the projected volume mounted at
// /var/run/secrets/tokens/dbaas/token. The pod deployment must include a
// projected volume with serviceAccountToken.audience=dbaas at that path.
func NewAggregatorClient(baseURL string) *AggregatorClient {
	return newClient(baseURL, func(ctx context.Context) (string, error) {
		return tokensource.GetAudienceToken(ctx, tokensource.AudienceDBaaS)
	})
}

// NewClientWithTokenFunc creates an AggregatorClient that uses the given function
// to obtain a Bearer token on every request. Intended for use in tests where the
// global tokensource state (projected volume file) is not available.
func NewClientWithTokenFunc(baseURL string, getToken func(ctx context.Context) (string, error)) *AggregatorClient {
	return newClient(baseURL, getToken)
}

// newClient is the internal constructor used in package-level tests.
func newClient(baseURL string, getToken func(ctx context.Context) (string, error)) *AggregatorClient {
	c := &AggregatorClient{getToken: getToken}

	c.rc = resty.New().
		SetBaseURL(baseURL).
		SetTimeout(defaultTimeout).
		SetHeader("Accept", "application/json").
		OnBeforeRequest(func(_ *resty.Client, r *resty.Request) error {
			token, err := c.getToken(r.Context())
			if err != nil {
				return fmt.Errorf("get dbaas audience token: %w", err)
			}
			r.SetAuthToken(token)
			return nil
		})

	return c
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

	return decodeResponse(resp.Body(), "apply")
}

// GetOperationStatus polls the status of an asynchronous operation.
//
// The trackingId is obtained from a previous ApplyConfig call.  The caller
// should keep calling this method while the returned Status is TaskStateInProgress.
//
// Returns *AggregatorError on non-2xx.
func (c *AggregatorClient) GetOperationStatus(ctx context.Context, trackingID string) (*DeclarativeResponse, error) {
	resp, err := c.rc.R().
		SetContext(ctx).
		Get(fmt.Sprintf("/api/declarations/v1/operation/%s/status", trackingID))
	if err != nil {
		return nil, err
	}

	if resp.StatusCode() != http.StatusOK {
		return nil, newAggregatorError(resp)
	}

	return decodeResponse(resp.Body(), "operation status")
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

func decodeResponse(body []byte, label string) (*DeclarativeResponse, error) {
	var result DeclarativeResponse
	if len(body) > 0 {
		if err := json.Unmarshal(body, &result); err != nil {
			return nil, fmt.Errorf("decode %s response: %w", label, err)
		}
	}
	return &result, nil
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
