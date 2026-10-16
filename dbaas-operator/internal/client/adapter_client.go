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

package client

import (
	"context"
	"errors"
	"fmt"
	"net/http"

	"github.com/go-resty/resty/v2"
)

// adapterPhysicalDatabasePath is the endpoint where an adapter describes the
// physical database it serves. It takes no {type}: the type is part of the answer.
const adapterPhysicalDatabasePath = "/api/v2/adapter/physical_database"

// featureMultiusers is the feature flag dbaas-aggregator reads without a null
// check whenever the reported roles differ from the stored ones.
const featureMultiusers = "multiusers"

// ErrAdapterResponseIncomplete reports a 200 answer that lacks a field the
// registration needs. The adapter answered, but not with anything the operator
// can register, so the controller treats it as a transient failure.
var ErrAdapterResponseIncomplete = errors.New("dbaas adapter answered the physical database request without a required field")

// AdapterCredentials is the adapter's own Basic Auth pair, read from the Secret
// referenced by a PhysicalDatabase.
type AdapterCredentials struct {
	Username string
	Password string
}

// AdapterPhysicalDatabase is the adapter's description of the physical database
// it serves, as returned by GET /api/v2/adapter/physical_database.
type AdapterPhysicalDatabase struct {
	PhysicalDatabaseID string            `json:"physicalDatabaseId"`
	Type               string            `json:"type"`
	Labels             map[string]string `json:"labels,omitempty"`
	APIVersions        *APIVersions      `json:"apiVersions,omitempty"`
	Features           map[string]bool   `json:"features"`
	SupportedRoles     []string          `json:"supportedRoles"`
	ReadOnlyHost       string            `json:"readOnlyHost,omitempty"`
}

// AdapterClient calls dbaas adapters directly. Unlike AggregatorClient it has no
// base URL and no shared credentials: every call names the adapter address and
// carries the credentials of the resource it is made for.
//
// It never logs request or response bodies. It is safe for concurrent use.
type AdapterClient struct {
	rc *resty.Client
}

// NewAdapterClient creates an AdapterClient with the same timeout and request
// context propagation as AggregatorClient.
func NewAdapterClient() *AdapterClient {
	return &AdapterClient{
		rc: resty.New().
			SetTimeout(defaultTimeout).
			SetHeader("Accept", "application/json").
			// Adapter traffic is in-cluster and Basic Auth over HTTP is the default,
			// as for the aggregator, so resty's per-request warning is noise.
			SetDisableWarn(true).
			OnBeforeRequest(func(_ *resty.Client, r *resty.Request) error {
				return applyRequestContext(r)
			}),
	}
}

// GetPhysicalDatabase asks the adapter at address which physical database it
// serves: GET {address}/api/v2/adapter/physical_database with the given Basic
// Auth pair. address is used as is, the way dbaas-aggregator uses it.
//
// Returns *AdapterError on a non-200 response, *RequestContextError when the
// request context is not initialized, and an error wrapping
// ErrAdapterResponseIncomplete when the answer lacks a field the registration needs.
func (c *AdapterClient) GetPhysicalDatabase(
	ctx context.Context, address string, creds AdapterCredentials,
) (*AdapterPhysicalDatabase, error) {
	resp, err := c.rc.R().
		SetContext(ctx).
		SetBasicAuth(creds.Username, creds.Password).
		Get(address + adapterPhysicalDatabasePath)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode() != http.StatusOK {
		return nil, newAdapterError(resp)
	}
	// An empty body decodes to a zero value and is reported as incomplete.
	body, err := decodeInto[AdapterPhysicalDatabase](resp.Body(), "adapter physical database", true, false)
	if err != nil {
		return nil, err
	}
	if missing := missingPhysicalDatabaseField(body); missing != "" {
		return nil, fmt.Errorf("%w: %s", ErrAdapterResponseIncomplete, missing)
	}
	return body, nil
}

// missingPhysicalDatabaseField names the first field the registration cannot do
// without, or returns "" when every one is present. physicalDatabaseId and type
// build the registration URL; the aggregator dereferences supportedRoles and
// features.multiusers without a null check and answers 500 without them.
func missingPhysicalDatabaseField(pd *AdapterPhysicalDatabase) string {
	switch {
	case pd.PhysicalDatabaseID == "":
		return "physicalDatabaseId"
	case pd.Type == "":
		return "type"
	case len(pd.SupportedRoles) == 0:
		return "supportedRoles"
	}
	if _, ok := pd.Features[featureMultiusers]; !ok {
		return "features." + featureMultiusers
	}
	return ""
}

func newAdapterError(resp *resty.Response) *AdapterError {
	code, message := parseTmf(resp.Body())
	return &AdapterError{
		StatusCode: resp.StatusCode(),
		Body:       string(resp.Body()),
		TmfCode:    code,
		TmfMessage: message,
	}
}
