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
	"slices"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/go-resty/resty/v2"
	"github.com/netcracker/qubership-core-lib-go-error-handling/v3/tmf"
	"github.com/netcracker/qubership-core-lib-go/v3/security/tokensource"
)

const defaultTimeout = 30 * time.Second

// AggregatorClient is an HTTP client for the dbaas-aggregator REST API.
// It authenticates in one of two mutually exclusive modes, selected at
// construction to mirror the aggregator's KUBERNETES_M2M_ENABLED setting:
//   - M2M (Bearer): a Kubernetes projected service account token with audience
//     "dbaas", fetched fresh on every request via the tokensource library;
//   - Basic Auth: a username/password pair loaded from the mounted security
//     Secret, hot-swappable at runtime via SetCredentials.
//
// It is safe for concurrent use.
type AggregatorClient struct {
	rc *resty.Client
	// getToken is set in M2M mode and nil in Basic Auth mode.
	getToken func(ctx context.Context) (string, error)
	// creds holds the Basic Auth pair in Basic Auth mode; nil in M2M mode.
	creds atomic.Pointer[credentials]
}

// credentials holds the HTTP Basic Auth pair as an immutable value, swapped
// atomically on credential reload.
type credentials struct {
	username, password string
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

// NewBasicAuthClient creates an AggregatorClient that authenticates with HTTP
// Basic Auth. Used when M2M token auth is disabled (KUBERNETES_M2M_ENABLED=false),
// in which case the aggregator rejects Bearer tokens and expects Basic credentials.
// The credentials can be hot-swapped at runtime via SetCredentials, so a mounted
// Secret update is picked up without a pod restart.
func NewBasicAuthClient(baseURL, username, password string) *AggregatorClient {
	c := newClient(baseURL, nil)
	c.creds.Store(&credentials{username: username, password: password})
	return c
}

// SetCredentials atomically replaces the Basic Auth credentials used for all
// subsequent requests. Safe for concurrent use.
func (c *AggregatorClient) SetCredentials(username, password string) {
	c.creds.Store(&credentials{username: username, password: password})
}

// newClient is the internal constructor used in package-level tests. A non-nil
// getToken selects M2M (Bearer) mode; nil selects Basic Auth mode, in which case
// the caller must seed credentials via creds.Store / SetCredentials.
func newClient(baseURL string, getToken func(ctx context.Context) (string, error)) *AggregatorClient {
	c := &AggregatorClient{getToken: getToken}

	c.rc = resty.New().
		SetBaseURL(baseURL).
		SetTimeout(defaultTimeout).
		SetHeader("Accept", "application/json").
		OnBeforeRequest(func(_ *resty.Client, r *resty.Request) error {
			// M2M mode — fetch a fresh dbaas-audience token per request.
			if c.getToken != nil {
				token, err := c.getToken(r.Context())
				if err != nil {
					return fmt.Errorf("get dbaas audience token: %w", err)
				}
				r.SetAuthToken(token)
				return nil
			}
			// Basic Auth mode — apply the currently loaded credentials.
			if cr := c.creds.Load(); cr != nil {
				r.SetBasicAuth(cr.username, cr.password)
			}
			return nil
		})

	return c
}

// doRequest sends method+path to the aggregator through the shared resty client
// (so the auth OnBeforeRequest hook applies), with ctx, an optional JSON body, and
// an optional prep hook to tweak the request (e.g. query params). It returns the
// response only when its status is in okCodes; otherwise an *AggregatorError. It
// centralizes the transport + status-check boilerplate shared by every endpoint;
// per-endpoint semantics (verb, OK codes, whether/what to decode) stay at the call site.
func (c *AggregatorClient) doRequest(
	ctx context.Context, method, path string, body any, prep func(*resty.Request), okCodes ...int,
) (*resty.Response, error) {
	r := c.rc.R().SetContext(ctx)
	if body != nil {
		r.SetBody(body)
	}
	if prep != nil {
		prep(r)
	}
	resp, err := r.Execute(method, path)
	if err != nil {
		return nil, err
	}
	if slices.Contains(okCodes, resp.StatusCode()) {
		return resp, nil
	}
	return nil, newAggregatorError(resp)
}

// decodeInto unmarshals an aggregator response body into a *T.
//
// When allowEmpty is true an empty body yields the zero value — the declarative
// apply/operation-status endpoints may legitimately return a success status with
// no payload. When allowEmpty is false an empty body is a decode error: callers
// that always expect a JSON object (get-by-classifier, the changed-databases feed)
// must NOT treat an empty 200 as a valid zero value. For the feed in particular a
// zero value reads as "no rotation history" and would seed the poller cursor at
// epoch, triggering a full replay instead of a retry. label names the endpoint.
func decodeInto[T any](body []byte, label string, allowEmpty bool) (*T, error) {
	var result T
	if len(body) == 0 {
		if allowEmpty {
			return &result, nil
		}
		return nil, fmt.Errorf("decode %s response: empty body", label)
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("decode %s response: %w", label, err)
	}
	return &result, nil
}

// ApplyConfig posts a declarative payload to POST /api/declarations/v1/apply.
//
// The caller constructs the payload with the appropriate kind/subKind/spec for
// the resource (InternalDatabase or DatabaseAccessPolicy).
//
// Return semantics:
//   - response.TrackingID != "" → operation is asynchronous (HTTP 202 from the
//     aggregator); call GetOperationStatus to poll for completion.
//   - response.TrackingID == "" → operation completed synchronously (HTTP 200);
//     inspect response.Conditions for the outcome.
//   - error (*AggregatorError) → non-2xx response; IsSpecRejection() distinguishes
//     a permanent spec error (400/403/409/410/422) from a transient failure.
func (c *AggregatorClient) ApplyConfig(ctx context.Context, payload *DeclarativePayload) (*DeclarativeResponse, error) {
	resp, err := c.doRequest(ctx, http.MethodPost, "/api/declarations/v1/apply", payload, nil,
		http.StatusOK, http.StatusAccepted)
	if err != nil {
		return nil, err
	}
	return decodeInto[DeclarativeResponse](resp.Body(), "apply", true)
}

// GetOperationStatus polls the status of an asynchronous operation.
//
// The trackingId is obtained from a previous ApplyConfig call.  The caller
// should keep calling this method while the returned Status is TaskStateInProgress.
//
// Returns *AggregatorError on non-2xx.
func (c *AggregatorClient) GetOperationStatus(ctx context.Context, trackingID string) (*DeclarativeResponse, error) {
	resp, err := c.doRequest(ctx, http.MethodGet,
		fmt.Sprintf("/api/declarations/v1/operation/%s/status", trackingID), nil, nil,
		http.StatusOK)
	if err != nil {
		return nil, err
	}
	return decodeInto[DeclarativeResponse](resp.Body(), "operation status", true)
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
	_, err := c.doRequest(ctx, http.MethodPut,
		fmt.Sprintf("/api/v3/dbaas/%s/databases/registration/externally_manageable", namespace), req, nil,
		http.StatusOK, http.StatusCreated)
	return err
}

// ApplyMicroserviceBalancingRules sends on-microservice balancing rules to
// PUT /api/v3/dbaas/{namespace}/physical_databases/rules/onMicroservices.
func (c *AggregatorClient) ApplyMicroserviceBalancingRules(ctx context.Context, namespace string, req []OnMicroserviceRuleRequest) error {
	_, err := c.doRequest(ctx, http.MethodPut,
		fmt.Sprintf("/api/v3/dbaas/%s/physical_databases/rules/onMicroservices", namespace), req, nil,
		http.StatusOK, http.StatusCreated)
	return err
}

// ApplyNamespaceBalancingRule sends one namespace balancing rule to
// PUT /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}.
func (c *AggregatorClient) ApplyNamespaceBalancingRule(ctx context.Context, namespace, ruleName string, req *NamespaceBalancingRuleRequest) error {
	_, err := c.doRequest(ctx, http.MethodPut,
		fmt.Sprintf("/api/v3/dbaas/%s/physical_databases/balancing/rules/%s", namespace, ruleName), req, nil,
		http.StatusOK, http.StatusCreated)
	return err
}

// DeleteNamespaceBalancingRule deletes one namespace balancing rule with
// DELETE /api/v3/dbaas/{namespace}/physical_databases/balancing/rules/{ruleName}.
func (c *AggregatorClient) DeleteNamespaceBalancingRule(ctx context.Context, namespace, ruleName string) error {
	_, err := c.doRequest(ctx, http.MethodDelete,
		fmt.Sprintf("/api/v3/dbaas/%s/physical_databases/balancing/rules/%s", namespace, ruleName), nil, nil,
		http.StatusOK, http.StatusNoContent, http.StatusNotFound)
	return err
}

// ApplyPermanentBalancingRules sends permanent balancing rules to
// PUT /api/v3/dbaas/balancing/rules/permanent.
func (c *AggregatorClient) ApplyPermanentBalancingRules(ctx context.Context, req []PermanentBalancingRuleRequest) error {
	_, err := c.doRequest(ctx, http.MethodPut, "/api/v3/dbaas/balancing/rules/permanent", req, nil,
		http.StatusOK, http.StatusCreated)
	return err
}

// DeletePermanentBalancingRules deletes permanent balancing rules with
// DELETE /api/v3/dbaas/balancing/rules/permanent.
func (c *AggregatorClient) DeletePermanentBalancingRules(ctx context.Context, req []PermanentBalancingRuleDeleteRequest) error {
	_, err := c.doRequest(ctx, http.MethodDelete, "/api/v3/dbaas/balancing/rules/permanent", req, nil,
		http.StatusOK, http.StatusNoContent)
	return err
}

// GetDatabaseByClassifier fetches connection properties for a database.
// POST /api/v3/dbaas/{namespace}/databases/get-by-classifier/{dbType}
// Returns *AggregatorError on non-2xx.
func (c *AggregatorClient) GetDatabaseByClassifier(
	ctx context.Context, namespace, dbType string, req *GetByClassifierRequest,
) (*DatabaseResponseSingleCP, error) {
	resp, err := c.doRequest(ctx, http.MethodPost,
		fmt.Sprintf("/api/v3/dbaas/%s/databases/get-by-classifier/%s", namespace, dbType), req, nil,
		http.StatusOK)
	if err != nil {
		return nil, err
	}
	return decodeInto[DatabaseResponseSingleCP](resp.Body(), "get-by-classifier", false)
}

// GetChangedSince fetches databases whose credentials changed (password rotation
// or restore) strictly after the given keyset cursor, ordered by (lastRotatedAt,
// id). GET /api/v3/dbaas/databases/changed?sinceTs={iso}&sinceId={uuid}&limit={n}
//
// Pass cursor=nil on the first call to receive only the current high-water mark
// (an empty Items list) — use it to seed the cursor without replaying history.
// limit <= 0 lets the aggregator apply its default page size.
// Requires the caller identity to hold the CLUSTER_OPERATOR role.
// Returns *AggregatorError on non-2xx.
func (c *AggregatorClient) GetChangedSince(ctx context.Context, cursor *ChangeCursor, limit int) (*ChangedDatabasesResponse, error) {
	resp, err := c.doRequest(ctx, http.MethodGet, "/api/v3/dbaas/databases/changed", nil,
		func(r *resty.Request) {
			if cursor != nil {
				r.SetQueryParam("sinceTs", cursor.LastRotatedAt.UTC().Format(time.RFC3339Nano))
				r.SetQueryParam("sinceId", cursor.Id)
			}
			if limit > 0 {
				r.SetQueryParam("limit", strconv.Itoa(limit))
			}
		},
		http.StatusOK)
	if err != nil {
		return nil, err
	}
	return decodeInto[ChangedDatabasesResponse](resp.Body(), "changed-databases", false)
}

func newAggregatorError(resp *resty.Response) *AggregatorError {
	aggErr := &AggregatorError{
		StatusCode: resp.StatusCode(),
		Body:       string(resp.Body()),
	}

	var tmfResp tmf.Response
	if json.Unmarshal(resp.Body(), &tmfResp) == nil {
		aggErr.TmfCode = tmfResp.Code
		if tmfResp.Message != "" {
			aggErr.TmfMessage = tmfResp.Message
		}
	}

	return aggErr
}
