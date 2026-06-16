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
	"testing"
	"time"
)

func tp(t time.Time) *time.Time { return &t }

func TestGetChangedSince_SeedCallOmitsSinceParam(t *testing.T) {
	hwm := time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC)
	var gotMethod, gotPath, gotSince, gotLimit string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		gotSince = r.URL.Query().Get("since")
		gotLimit = r.URL.Query().Get("limit")
		writeJSON(t, w, http.StatusOK, ChangedDatabasesResponse{HighWaterMark: &hwm})
	}))
	defer srv.Close()

	c := NewClientWithTokenFunc(srv.URL, staticToken("tok"))
	resp, err := c.GetChangedSince(context.Background(), nil, 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodGet {
		t.Errorf("method = %s, want GET", gotMethod)
	}
	if gotPath != "/api/v3/dbaas/databases/changed" {
		t.Errorf("path = %s", gotPath)
	}
	if gotSince != "" {
		t.Errorf("since must be omitted on the seed call, got %q", gotSince)
	}
	if gotLimit != "" {
		t.Errorf("limit must be omitted when 0, got %q", gotLimit)
	}
	if resp.HighWaterMark == nil || !resp.HighWaterMark.Equal(hwm) {
		t.Errorf("highWaterMark = %v, want %v", resp.HighWaterMark, hwm)
	}
	if len(resp.Items) != 0 {
		t.Errorf("items = %d, want 0", len(resp.Items))
	}
}

func TestGetChangedSince_SendsSinceAndLimitAndParsesItems(t *testing.T) {
	since := time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC)
	next := since.Add(time.Minute)
	var gotSince, gotLimit string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotSince = r.URL.Query().Get("since")
		gotLimit = r.URL.Query().Get("limit")
		writeJSON(t, w, http.StatusOK, ChangedDatabasesResponse{
			Items: []ChangedDatabaseRef{{
				Namespace:     "ns",
				Classifier:    map[string]any{"microserviceName": "ms", "scope": "service", "namespace": "ns"},
				Type:          "postgresql",
				LastRotatedAt: next,
			}},
			HighWaterMark: tp(next),
		})
	}))
	defer srv.Close()

	c := NewClientWithTokenFunc(srv.URL, staticToken("tok"))
	resp, err := c.GetChangedSince(context.Background(), &since, 100)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if want := since.Format(time.RFC3339Nano); gotSince != want {
		t.Errorf("since = %q, want %q", gotSince, want)
	}
	if gotLimit != "100" {
		t.Errorf("limit = %q, want 100", gotLimit)
	}
	if len(resp.Items) != 1 {
		t.Fatalf("items = %d, want 1", len(resp.Items))
	}
	if resp.Items[0].Type != "postgresql" || resp.Items[0].Namespace != "ns" || !resp.Items[0].LastRotatedAt.Equal(next) {
		t.Errorf("item = %+v", resp.Items[0])
	}
}

func TestGetChangedSince_NonOKReturnsAggregatorError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()

	c := NewClientWithTokenFunc(srv.URL, staticToken("tok"))
	_, err := c.GetChangedSince(context.Background(), nil, 0)
	if err == nil {
		t.Fatal("expected an error on HTTP 403")
	}
	var aggErr *AggregatorError
	if !errors.As(err, &aggErr) || aggErr.StatusCode != http.StatusForbidden {
		t.Errorf("want *AggregatorError with 403, got %v", err)
	}
}
