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

func TestGetChangedSince_SeedCallOmitsCursorParams(t *testing.T) {
	hwm := ChangeCursor{LastRotatedAt: time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC), Id: "11111111-1111-1111-1111-111111111111"}
	var gotMethod, gotPath, gotSinceTs, gotSinceID, gotLimit string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		gotSinceTs = r.URL.Query().Get("sinceTs")
		gotSinceID = r.URL.Query().Get("sinceId")
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
	if gotSinceTs != "" || gotSinceID != "" {
		t.Errorf("cursor params must be omitted on the seed call, got sinceTs=%q sinceId=%q", gotSinceTs, gotSinceID)
	}
	if gotLimit != "" {
		t.Errorf("limit must be omitted when 0, got %q", gotLimit)
	}
	if resp.HighWaterMark == nil || !resp.HighWaterMark.LastRotatedAt.Equal(hwm.LastRotatedAt) || resp.HighWaterMark.Id != hwm.Id {
		t.Errorf("highWaterMark = %+v, want %+v", resp.HighWaterMark, hwm)
	}
	if len(resp.Items) != 0 {
		t.Errorf("items = %d, want 0", len(resp.Items))
	}
}

func TestGetChangedSince_SendsCursorParamsAndParsesItems(t *testing.T) {
	cursor := ChangeCursor{LastRotatedAt: time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC), Id: "22222222-2222-2222-2222-222222222222"}
	next := cursor.LastRotatedAt.Add(time.Minute)
	var gotSinceTs, gotSinceID, gotLimit string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotSinceTs = r.URL.Query().Get("sinceTs")
		gotSinceID = r.URL.Query().Get("sinceId")
		gotLimit = r.URL.Query().Get("limit")
		writeJSON(t, w, http.StatusOK, ChangedDatabasesResponse{
			Items: []ChangedDatabaseRef{{
				Id:            "33333333-3333-3333-3333-333333333333",
				Namespace:     "ns",
				Classifier:    map[string]any{"microserviceName": "ms", "scope": "service", "namespace": "ns"},
				Type:          "postgresql",
				LastRotatedAt: next,
			}},
		})
	}))
	defer srv.Close()

	c := NewClientWithTokenFunc(srv.URL, staticToken("tok"))
	resp, err := c.GetChangedSince(context.Background(), &cursor, 100)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if want := cursor.LastRotatedAt.Format(time.RFC3339Nano); gotSinceTs != want {
		t.Errorf("sinceTs = %q, want %q", gotSinceTs, want)
	}
	if gotSinceID != cursor.Id {
		t.Errorf("sinceId = %q, want %q", gotSinceID, cursor.Id)
	}
	if gotLimit != "100" {
		t.Errorf("limit = %q, want 100", gotLimit)
	}
	if len(resp.Items) != 1 {
		t.Fatalf("items = %d, want 1", len(resp.Items))
	}
	got := resp.Items[0]
	if got.Id != "33333333-3333-3333-3333-333333333333" || got.Type != "postgresql" || got.Namespace != "ns" || !got.LastRotatedAt.Equal(next) {
		t.Errorf("item = %+v", got)
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
