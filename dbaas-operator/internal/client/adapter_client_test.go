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
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/baseproviders/xrequestid"
)

var testAdapterCredentials = AdapterCredentials{Username: "dbaas-aggregator", Password: "adapter-secret"}

// fullAdapterAnswer is the adapter answer from the design document.
const fullAdapterAnswer = `{
  "physicalDatabaseId": "core-postgresql",
  "type": "postgresql",
  "labels": {"clusterName": "postgres-core"},
  "apiVersions": {"specs": [{"specRootUrl": "/api", "major": 2, "minor": 1, "supportedMajors": [2]}]},
  "features": {"multiusers": true, "tls": true},
  "supportedRoles": ["admin", "rw", "ro"],
  "readOnlyHost": "pg-patroni-ro.postgres"
}`

func TestGetPhysicalDatabase_SendsRequestAndDecodesAnswer(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath, gotRequestID string
	var gotUser, gotPassword string
	var gotBasicAuth bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		gotRequestID = r.Header.Get(xrequestid.X_REQUEST_ID_HEADER_NAME)
		gotUser, gotPassword, gotBasicAuth = r.BasicAuth()
		_, _ = w.Write([]byte(fullAdapterAnswer))
	}))
	defer srv.Close()

	got, err := NewAdapterClient().GetPhysicalDatabase(
		contextWithRequestID("adapter-probe-request-id"), srv.URL, testAdapterCredentials)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := &AdapterPhysicalDatabase{
		PhysicalDatabaseID: "core-postgresql",
		Type:               "postgresql",
		Labels:             map[string]string{"clusterName": "postgres-core"},
		APIVersions: &APIVersions{Specs: []APIVersionSpec{
			{SpecRootURL: "/api", Major: 2, Minor: 1, SupportedMajors: []int32{2}},
		}},
		Features:       map[string]bool{"multiusers": true, "tls": true},
		SupportedRoles: []string{"admin", "rw", "ro"},
		ReadOnlyHost:   "pg-patroni-ro.postgres",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("answer = %#v\nwant %#v", got, want)
	}
	if gotMethod != http.MethodGet {
		t.Errorf("method = %s, want GET", gotMethod)
	}
	if want := "/api/v2/adapter/physical_database"; gotPath != want {
		t.Errorf("path = %s, want %s", gotPath, want)
	}
	if gotRequestID != "adapter-probe-request-id" {
		t.Errorf("X-Request-Id = %q, want adapter-probe-request-id", gotRequestID)
	}
	if !gotBasicAuth || gotUser != testAdapterCredentials.Username || gotPassword != testAdapterCredentials.Password {
		t.Errorf("basic auth = (%q, %q, %v), want the adapter credentials", gotUser, gotPassword, gotBasicAuth)
	}
}

func TestGetPhysicalDatabase_IncompleteAnswer(t *testing.T) {
	const roles = `"supportedRoles":["admin"]`
	const features = `"features":{"multiusers":false}`
	tests := []struct {
		name        string
		body        string
		wantMissing string // "" for an answer that is not JSON at all
	}{
		{name: "empty body", body: ``, wantMissing: "physicalDatabaseId"},
		{name: "no id", body: `{"type":"postgresql",` + roles + `,` + features + `}`, wantMissing: "physicalDatabaseId"},
		{name: "no type", body: `{"physicalDatabaseId":"pg",` + roles + `,` + features + `}`, wantMissing: "type"},
		{
			name:        "empty supportedRoles",
			body:        `{"physicalDatabaseId":"pg","type":"postgresql","supportedRoles":[],` + features + `}`,
			wantMissing: "supportedRoles",
		},
		{
			name:        "no features",
			body:        `{"physicalDatabaseId":"pg","type":"postgresql",` + roles + `}`,
			wantMissing: "features.multiusers",
		},
		{
			name:        "features without multiusers",
			body:        `{"physicalDatabaseId":"pg","type":"postgresql",` + roles + `,"features":{"tls":true}}`,
			wantMissing: "features.multiusers",
		},
		{name: "not JSON", body: `<html>ok</html>`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				_, _ = w.Write([]byte(tt.body))
			}))
			defer srv.Close()

			_, err := NewAdapterClient().GetPhysicalDatabase(requestContext(), srv.URL, testAdapterCredentials)
			if err == nil {
				t.Fatal("expected an error, got nil")
			}
			var adapterErr *AdapterError
			if errors.As(err, &adapterErr) {
				t.Errorf("error = %v, want a transient error rather than *AdapterError", err)
			}
			wantIncomplete := tt.wantMissing != ""
			if got := errors.Is(err, ErrAdapterResponseIncomplete); got != wantIncomplete {
				t.Errorf("errors.Is(err, ErrAdapterResponseIncomplete) = %v, want %v (err: %v)", got, wantIncomplete, err)
			}
			if wantIncomplete && !strings.HasSuffix(err.Error(), ": "+tt.wantMissing) {
				t.Errorf("error = %q, want it to name %s", err, tt.wantMissing)
			}
		})
	}
}

// TestGetPhysicalDatabase_ErrorClassification checks that adapter responses are
// classified with the same status codes as aggregator responses.
func TestGetPhysicalDatabase_ErrorClassification(t *testing.T) {
	tests := []struct {
		status           int
		wantAuthErr      bool
		wantSpecRejected bool
	}{
		{status: http.StatusUnauthorized, wantAuthErr: true},
		{status: http.StatusBadRequest, wantSpecRejected: true},
		{status: http.StatusForbidden, wantSpecRejected: true},
		{status: http.StatusConflict, wantSpecRejected: true},
		{status: http.StatusGone, wantSpecRejected: true},
		{status: http.StatusUnprocessableEntity, wantSpecRejected: true},
		{status: http.StatusNotFound},
		{status: http.StatusMethodNotAllowed},
		{status: http.StatusTooManyRequests},
		{status: http.StatusInternalServerError},
		{status: http.StatusServiceUnavailable},
	}
	for _, tt := range tests {
		t.Run(http.StatusText(tt.status), func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tt.status)
				_, _ = w.Write([]byte(`{"code":"ADAPTER-1","message":"adapter says no"}`))
			}))
			defer srv.Close()

			_, err := NewAdapterClient().GetPhysicalDatabase(requestContext(), srv.URL, testAdapterCredentials)
			var adapterErr *AdapterError
			if !errors.As(err, &adapterErr) {
				t.Fatalf("error = %v (%T), want *AdapterError", err, err)
			}
			if adapterErr.StatusCode != tt.status {
				t.Errorf("StatusCode = %d, want %d", adapterErr.StatusCode, tt.status)
			}
			if adapterErr.IsAuthError() != tt.wantAuthErr {
				t.Errorf("IsAuthError() = %v, want %v", adapterErr.IsAuthError(), tt.wantAuthErr)
			}
			if adapterErr.IsSpecRejection() != tt.wantSpecRejected {
				t.Errorf("IsSpecRejection() = %v, want %v", adapterErr.IsSpecRejection(), tt.wantSpecRejected)
			}
			if got := adapterErr.UserMessage(); got != "adapter says no" {
				t.Errorf("UserMessage() = %q, want the TMF message", got)
			}
			aggErr := &AggregatorError{StatusCode: tt.status}
			if aggErr.IsAuthError() != adapterErr.IsAuthError() || aggErr.IsSpecRejection() != adapterErr.IsSpecRejection() {
				t.Errorf("HTTP %d is classified differently for the adapter and the aggregator", tt.status)
			}
		})
	}
}

func TestGetPhysicalDatabase_UserMessageFallsBackToBody(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("plain failure"))
	}))
	defer srv.Close()

	_, err := NewAdapterClient().GetPhysicalDatabase(requestContext(), srv.URL, testAdapterCredentials)
	var adapterErr *AdapterError
	if !errors.As(err, &adapterErr) {
		t.Fatalf("error = %v (%T), want *AdapterError", err, err)
	}
	if got := adapterErr.UserMessage(); got != "plain failure" {
		t.Errorf("UserMessage() = %q, want the raw body", got)
	}
}

func TestGetPhysicalDatabase_TransportFailureIsNotAdapterError(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(requestContext(), 100*time.Millisecond)
	defer cancel()
	_, err := NewAdapterClient().GetPhysicalDatabase(ctx, srv.URL, testAdapterCredentials)
	if err == nil {
		t.Fatal("expected an error, got nil")
	}
	var adapterErr *AdapterError
	if errors.As(err, &adapterErr) {
		t.Errorf("error = %v, want a transport error rather than *AdapterError", err)
	}
}

func TestGetPhysicalDatabase_RejectsMissingRequestID(t *testing.T) {
	t.Parallel()

	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Error("request without X-Request-Id reached the adapter")
	}))
	defer srv.Close()

	_, err := NewAdapterClient().GetPhysicalDatabase(context.Background(), srv.URL, testAdapterCredentials)
	var requestContextErr *RequestContextError
	if !errors.As(err, &requestContextErr) {
		t.Fatalf("error = %v (%T), want *RequestContextError", err, err)
	}
}
