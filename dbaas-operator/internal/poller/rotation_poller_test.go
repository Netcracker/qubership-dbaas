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

// fakeSource is a stub ChangedSource that records the cursors it was called with
// and returns a fixed response/error.
type fakeSource struct {
	resp      *aggregatorclient.ChangedDatabasesResponse
	err       error
	gotCursor []*aggregatorclient.ChangeCursor
}

func (f *fakeSource) GetChangedSince(_ context.Context, cursor *aggregatorclient.ChangeCursor, _ int) (*aggregatorclient.ChangedDatabasesResponse, error) {
	f.gotCursor = append(f.gotCursor, cursor)
	if f.err != nil {
		return nil, f.err
	}
	return f.resp, nil
}

func TestPollOnce_SeedFromHighWaterMark(t *testing.T) {
	hwm := aggregatorclient.ChangeCursor{LastRotatedAt: time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC), Id: "aaaa"}
	src := &fakeSource{resp: &aggregatorclient.ChangedDatabasesResponse{HighWaterMark: &hwm}}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), nil, DefaultLimit)

	if cursor == nil || !cursor.LastRotatedAt.Equal(hwm.LastRotatedAt) || cursor.Id != hwm.Id {
		t.Fatalf("cursor = %+v, want high-water mark %+v", cursor, hwm)
	}
	if len(src.gotCursor) != 1 || src.gotCursor[0] != nil {
		t.Errorf("the seed call must pass a nil cursor, got %v", src.gotCursor)
	}
}

func TestPollOnce_SeedEpochWhenNothingRotated(t *testing.T) {
	src := &fakeSource{resp: &aggregatorclient.ChangedDatabasesResponse{HighWaterMark: nil}}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), nil, DefaultLimit)

	want := epochCursor()
	if cursor == nil || !cursor.LastRotatedAt.Equal(want.LastRotatedAt) || cursor.Id != want.Id {
		t.Fatalf("cursor = %+v, want epoch baseline %+v", cursor, want)
	}
}

func TestPollOnce_AdvancesCursorByKeysetThroughTies(t *testing.T) {
	start := aggregatorclient.ChangeCursor{LastRotatedAt: time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC), Id: "0000"}
	// Two items share the SAME timestamp but differ by id — the keyset cursor must
	// advance to the last id so the next poll does not re-fetch the same page.
	ts := start.LastRotatedAt.Add(time.Minute)
	src := &fakeSource{resp: &aggregatorclient.ChangedDatabasesResponse{
		Items: []aggregatorclient.ChangedDatabaseRef{
			{Id: "aaaa", Namespace: "ns", Classifier: map[string]any{"microserviceName": "a", "scope": "service", "namespace": "ns"}, Type: "postgresql", LastRotatedAt: ts},
			{Id: "bbbb", Namespace: "ns", Classifier: map[string]any{"microserviceName": "b", "scope": "service", "namespace": "ns"}, Type: "postgresql", LastRotatedAt: ts},
		},
	}}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), &start, DefaultLimit)

	if cursor == nil || !cursor.LastRotatedAt.Equal(ts) || cursor.Id != "bbbb" {
		t.Fatalf("cursor = %+v, want (%v, bbbb)", cursor, ts)
	}
	// The request must use the cursor verbatim — no timestamp-subtraction overlap.
	if len(src.gotCursor) != 1 || src.gotCursor[0] == nil ||
		!src.gotCursor[0].LastRotatedAt.Equal(start.LastRotatedAt) || src.gotCursor[0].Id != start.Id {
		t.Errorf("request cursor = %v, want verbatim %+v", src.gotCursor, start)
	}
}

func TestPollOnce_ErrorKeepsCursor(t *testing.T) {
	start := aggregatorclient.ChangeCursor{LastRotatedAt: time.Date(2026, 6, 16, 12, 0, 0, 0, time.UTC), Id: "cccc"}
	src := &fakeSource{err: errors.New("boom")}
	p := &RotationPoller{Client: newFakeClient(), Source: src}

	cursor := p.pollOnce(context.Background(), &start, DefaultLimit)

	if cursor == nil || !cursor.LastRotatedAt.Equal(start.LastRotatedAt) || cursor.Id != start.Id {
		t.Fatalf("cursor = %+v, want unchanged %+v on error", cursor, start)
	}
}
