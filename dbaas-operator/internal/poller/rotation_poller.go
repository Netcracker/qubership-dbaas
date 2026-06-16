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
	"time"

	"sigs.k8s.io/controller-runtime/pkg/client"

	aggregatorclient "github.com/netcracker/qubership-dbaas/dbaas-operator/internal/client"
)

const (
	// DefaultLimit is the page size requested per poll. Excess changes are
	// drained on subsequent ticks (the cursor advances by the returned items).
	DefaultLimit = 500

	// pollOverlap is subtracted from the cursor on each request so a registry
	// row that committed slightly out of timestamp order (the outbox-sequence
	// visibility gap) is still seen. Re-fetched rows are harmless: re-patching
	// the same trigger value does not change the annotation, so no extra
	// reconcile is triggered.
	pollOverlap = 5 * time.Second
)

// epoch is the baseline cursor used when nothing has rotated yet, so the first
// future rotation (timestamp > epoch) is caught. Computed without time.Now so
// the value is deterministic.
var epoch = time.Unix(0, 0).UTC()

// ChangedSource is the subset of the aggregator client the poller depends on.
// Defined as an interface so tests can supply a fake.
type ChangedSource interface {
	GetChangedSince(ctx context.Context, since *time.Time, limit int) (*aggregatorclient.ChangedDatabasesResponse, error)
}

// RotationPoller periodically pulls the aggregator's changed-databases feed and
// wakes the affected DatabaseSecretClaim reconciles. It replaces the inbound
// rotation webhook.
//
// Correctness does not depend on the poller catching every change: the operator's
// startup full reconcile syncs all CRs against current state, and the per-CR
// safety-net requeue is the ultimate backstop. The cursor is therefore in-memory
// — on restart or leader failover the new leader re-seeds from the aggregator's
// high-water mark and the startup sweep covers the gap.
type RotationPoller struct {
	// Client lists and patches DatabaseSecretClaim CRs. Must be backed by a
	// cache with the ClassifierTypeIndex registered.
	Client client.Client
	// Source is the aggregator changed-databases endpoint.
	Source ChangedSource
	// Interval is the polling period.
	Interval time.Duration
	// Limit is the page size; DefaultLimit when zero.
	Limit int
}

// NeedLeaderElection makes the poller run only on the elected leader, so a single
// cursor owner drives the fan-out.
func (p *RotationPoller) NeedLeaderElection() bool { return true }

// Start runs the poll loop until ctx is cancelled. It implements
// manager.Runnable.
func (p *RotationPoller) Start(ctx context.Context) error {
	limit := p.Limit
	if limit <= 0 {
		limit = DefaultLimit
	}
	log.Infof("Starting rotation poller interval=%v limit=%d", p.Interval, limit)

	// cursor is nil until seeded; pollOnce performs the seed (and re-seed after
	// a failed seed) when it sees a nil cursor.
	var cursor *time.Time

	ticker := time.NewTicker(p.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Infof("Stopping rotation poller")
			return nil
		case <-ticker.C:
			cursor = p.pollOnce(ctx, cursor, limit)
		}
	}
}

// pollOnce performs one poll iteration and returns the (possibly advanced) cursor.
// A nil cursor triggers a seed: the seed-only (since-less) call returns the
// aggregator's current high-water mark, which becomes the baseline so existing
// rotations — already synced by the startup reconcile — are not replayed.
func (p *RotationPoller) pollOnce(ctx context.Context, cursor *time.Time, limit int) *time.Time {
	if cursor == nil {
		resp, err := p.Source.GetChangedSince(ctx, nil, 0)
		if err != nil {
			log.ErrorC(ctx, "Rotation poller failed to seed cursor (will retry next tick): %v", err)
			return nil
		}
		if resp.HighWaterMark != nil {
			log.InfoC(ctx, "Rotation poller seeded cursor highWaterMark=%v", resp.HighWaterMark.UTC())
			return resp.HighWaterMark
		}
		// Nothing has rotated yet — baseline at epoch so the first future
		// rotation is caught.
		log.InfoC(ctx, "Rotation poller seeded cursor at epoch (no rotations recorded yet)")
		return &epoch
	}

	since := cursor.Add(-pollOverlap)
	resp, err := p.Source.GetChangedSince(ctx, &since, limit)
	if err != nil {
		log.ErrorC(ctx, "Rotation poller request failed (will retry next tick): %v", err)
		return cursor
	}

	advanced := cursor
	for i := range resp.Items {
		it := &resp.Items[i]
		trigger := it.LastRotatedAt.UTC().Format(time.RFC3339Nano)
		if _, _, perr := PatchClaimsForRotation(ctx, p.Client, it.Namespace, it.Classifier, it.Type, trigger); perr != nil {
			log.ErrorC(ctx, "Rotation poller failed to fan out namespace=%s type=%s err=%v",
				it.Namespace, it.Type, perr)
			continue
		}
		if it.LastRotatedAt.After(*advanced) {
			t := it.LastRotatedAt
			advanced = &t
		}
	}
	if len(resp.Items) > 0 {
		log.InfoC(ctx, "Rotation poller processed changes count=%d cursor=%v", len(resp.Items), advanced.UTC())
	}
	return advanced
}
