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
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func minimalPhysicalDatabaseRequest() *PhysicalDatabaseRegistrationRequest {
	return &PhysicalDatabaseRegistrationRequest{
		AdapterAddress:       "http://pg-dbaas-adapter.postgres:8080",
		HTTPBasicCredentials: HTTPBasicCredentials{Username: "dbaas-aggregator", Password: "adapter-secret"},
		Metadata: PhysicalDatabaseMetadata{
			APIVersion:     "v2",
			SupportedRoles: []string{"admin", "rw", "ro"},
			Features:       map[string]bool{"multiusers": true},
		},
		Status: "running",
	}
}

func TestRegisterPhysicalDatabase_SendsWireShape(t *testing.T) {
	t.Parallel()

	var gotMethod, gotPath, gotInternalMigration string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		gotInternalMigration = r.URL.Query().Get("internalMigration")
		raw, _ := io.ReadAll(r.Body)
		if err := json.Unmarshal(raw, &gotBody); err != nil {
			t.Errorf("request body is not JSON: %v", err)
		}
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	req := minimalPhysicalDatabaseRequest()
	req.Labels = map[string]string{"clusterName": "postgres-core"}
	req.Metadata.ROHost = "pg-patroni-ro.postgres"
	req.Metadata.APIVersions = &APIVersions{Specs: []APIVersionSpec{
		{SpecRootURL: "/api", Major: 2, Minor: 1, SupportedMajors: []int32{1, 2}},
	}}

	accepted, err := NewBasicAuthClient(srv.URL, "dbaas-operator", "s3cr3t").
		RegisterPhysicalDatabase(requestContext(), dbTypePostgresql, "core-postgresql", req)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if accepted {
		t.Error("accepted = true for 201, want false")
	}
	if gotMethod != http.MethodPut {
		t.Errorf("method = %s, want PUT", gotMethod)
	}
	if want := "/api/v3/dbaas/postgresql/physical_databases/core-postgresql"; gotPath != want {
		t.Errorf("path = %s, want %s", gotPath, want)
	}
	if gotInternalMigration != "true" {
		t.Errorf("internalMigration = %q, want \"true\"", gotInternalMigration)
	}

	want := map[string]any{
		"adapterAddress":       "http://pg-dbaas-adapter.postgres:8080",
		"httpBasicCredentials": map[string]any{"username": "dbaas-aggregator", "password": "adapter-secret"},
		"labels":               map[string]any{"clusterName": "postgres-core"},
		"status":               "running",
		"metadata": map[string]any{
			"apiVersion":     "v2",
			"supportedRoles": []any{"admin", "rw", "ro"},
			"features":       map[string]any{"multiusers": true},
			"roHost":         "pg-patroni-ro.postgres",
			"apiVersions": map[string]any{"specs": []any{map[string]any{
				"specRootUrl": "/api", "major": float64(2), "minor": float64(1),
				"supportedMajors": []any{float64(1), float64(2)},
			}}},
		},
	}
	if !reflect.DeepEqual(gotBody, want) {
		t.Errorf("body = %#v\nwant %#v", gotBody, want)
	}
}

func TestRegisterPhysicalDatabase_EscapesPathSegments(t *testing.T) {
	t.Parallel()

	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.EscapedPath()
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	// Both segments come from the adapter, so a slash must not add a path level.
	_, err := NewBasicAuthClient(srv.URL, "dbaas-operator", "s3cr3t").RegisterPhysicalDatabase(
		requestContext(), dbTypePostgresql, "core/postgresql", minimalPhysicalDatabaseRequest())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if want := "/api/v3/dbaas/postgresql/physical_databases/core%2Fpostgresql"; gotPath != want {
		t.Errorf("path = %s, want %s", gotPath, want)
	}
}

func TestRegisterPhysicalDatabase_OmitsEmptyOptionalFields(t *testing.T) {
	t.Parallel()

	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &gotBody)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	_, err := NewBasicAuthClient(srv.URL, "dbaas-operator", "s3cr3t").RegisterPhysicalDatabase(
		requestContext(), dbTypePostgresql, "core-postgresql", minimalPhysicalDatabaseRequest())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, ok := gotBody["labels"]; ok {
		t.Error("labels sent although empty")
	}
	metadata, _ := gotBody["metadata"].(map[string]any)
	for _, key := range []string{"roHost", "apiVersions"} {
		if _, ok := metadata[key]; ok {
			t.Errorf("metadata.%s sent although empty", key)
		}
	}
}

func TestRegisterPhysicalDatabase_StatusHandling(t *testing.T) {
	tests := []struct {
		name             string
		status           int
		body             string
		wantAccepted     bool
		wantAuthErr      bool
		wantSpecRejected bool
		wantTransientErr bool
	}{
		{name: "updated or unchanged", status: http.StatusOK},
		{name: "created", status: http.StatusCreated},
		{
			name:         "accepted, role migration pending",
			status:       http.StatusAccepted,
			body:         `{"instruction":{"id":"f7c1a2e4","additionalRoles":[]}}`,
			wantAccepted: true,
		},
		{name: "accepted, aggregator migrating", status: http.StatusAccepted, wantAccepted: true},
		{name: "malformed address", status: http.StatusBadRequest, body: `{"code":"CORE-DBAAS-4045"}`, wantSpecRejected: true},
		{name: "operator credentials rejected", status: http.StatusUnauthorized, wantAuthErr: true},
		{name: "identifier conflict", status: http.StatusConflict, body: `{"code":"CORE-DBAAS-4011"}`, wantSpecRejected: true},
		{name: "server error", status: http.StatusInternalServerError, wantTransientErr: true},
		{name: "adapter handshake failed", status: http.StatusBadGateway, wantTransientErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tt.status)
				_, _ = w.Write([]byte(tt.body))
			}))
			defer srv.Close()

			accepted, err := NewBasicAuthClient(srv.URL, "dbaas-operator", "s3cr3t").RegisterPhysicalDatabase(
				requestContext(), dbTypePostgresql, "core-postgresql", minimalPhysicalDatabaseRequest())

			if !tt.wantAuthErr && !tt.wantSpecRejected && !tt.wantTransientErr {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if accepted != tt.wantAccepted {
					t.Errorf("accepted = %v, want %v", accepted, tt.wantAccepted)
				}
				return
			}
			var aggErr *AggregatorError
			if !errors.As(err, &aggErr) {
				t.Fatalf("error = %v (%T), want *AggregatorError", err, err)
			}
			if aggErr.IsAuthError() != tt.wantAuthErr {
				t.Errorf("IsAuthError() = %v, want %v", aggErr.IsAuthError(), tt.wantAuthErr)
			}
			if aggErr.IsSpecRejection() != tt.wantSpecRejected {
				t.Errorf("IsSpecRejection() = %v, want %v", aggErr.IsSpecRejection(), tt.wantSpecRejected)
			}
		})
	}
}
