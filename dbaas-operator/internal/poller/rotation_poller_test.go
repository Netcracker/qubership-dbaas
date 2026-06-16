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

package poller

import (
	"context"
	"errors"
	"testing"
	"time"

	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

// fakeSource is a stub ChangedSource that records the since values it was called
// with and returns a fixed response/error.
type fakeSource struct {
	resp     *aggregatorclient.ChangedDatabasesResponse
	err      error
	gotSince []*time.Time
}

func (f *fakeSource) GetChangedSince(_ context.Context, since *time.Time, _ int) (*aggregatorclient.ChangedDatabasesResponse, error) {
	f.gotSince = append(f.gotSince, since)
	if f.err != nil {
		return nil, f.err
	}
	return f.resp, nil
}

func TestPollOnce_SeedFromHighWaterMark(t *testing.T) {
	hwm := time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC)
	src := &fakeSource{resp: &aggregatorclient.ChangedDatabasesResponse{HighWaterMark: &hwm}}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), nil, DefaultLimit)

	if cursor == nil || !cursor.Equal(hwm) {
		t.Fatalf("cursor = %v, want high-water mark %v", cursor, hwm)
	}
	if len(src.gotSince) != 1 || src.gotSince[0] != nil {
		t.Errorf("the seed call must pass a nil since, got %v", src.gotSince)
	}
}

func TestPollOnce_SeedEpochWhenNothingRotated(t *testing.T) {
	src := &fakeSource{resp: &aggregatorclient.ChangedDatabasesResponse{HighWaterMark: nil}}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), nil, DefaultLimit)

	if cursor == nil || !cursor.Equal(epoch) {
		t.Fatalf("cursor = %v, want epoch baseline", cursor)
	}
}

func TestPollOnce_AdvancesCursorFromItemsWithOverlap(t *testing.T) {
	start := time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC)
	t1 := start.Add(time.Minute)
	t2 := start.Add(2 * time.Minute)
	src := &fakeSource{resp: &aggregatorclient.ChangedDatabasesResponse{
		Items: []aggregatorclient.ChangedDatabaseRef{
			{Namespace: "ns", Classifier: map[string]any{"microserviceName": "a", "scope": "service", "namespace": "ns"}, Type: "postgresql", LastRotatedAt: t1},
			{Namespace: "ns", Classifier: map[string]any{"microserviceName": "b", "scope": "service", "namespace": "ns"}, Type: "postgresql", LastRotatedAt: t2},
		},
		HighWaterMark: &t2,
	}}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), &start, DefaultLimit)

	if cursor == nil || !cursor.Equal(t2) {
		t.Fatalf("cursor = %v, want latest item %v", cursor, t2)
	}
	wantSince := start.Add(-pollOverlap)
	if len(src.gotSince) != 1 || src.gotSince[0] == nil || !src.gotSince[0].Equal(wantSince) {
		t.Errorf("since = %v, want cursor minus overlap %v", src.gotSince, wantSince)
	}
}

func TestPollOnce_ErrorKeepsCursor(t *testing.T) {
	start := time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC)
	src := &fakeSource{err: errors.New("boom")}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), &start, DefaultLimit)

	if cursor == nil || !cursor.Equal(start) {
		t.Fatalf("cursor = %v, want unchanged %v on error", cursor, start)
	}
}
