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

// DefaultLimit is the page size requested per poll. Excess changes are drained on
// subsequent ticks — the keyset cursor advances by the last returned item, so a
// full page never stalls even when many rows share a timestamp.
const DefaultLimit = 500

// zeroUUID is the smallest UUID; paired with the epoch timestamp it forms the
// baseline cursor used when nothing has rotated yet, so the first future rotation
// sorts strictly after it. Computed without time.Now so the value is deterministic.
const zeroUUID = "00000000-0000-0000-0000-000000000000"

func epochCursor() aggregatorclient.ChangeCursor {
	return aggregatorclient.ChangeCursor{LastRotatedAt: time.Unix(0, 0).UTC(), ID: zeroUUID}
}

// ChangedSource is the subset of the aggregator client the poller depends on.
// Defined as an interface so tests can supply a fake.
type ChangedSource interface {
	GetChangedSince(ctx context.Context, cursor *aggregatorclient.ChangeCursor, limit int) (*aggregatorclient.ChangedDatabasesResponse, error)
}

// RotationPoller periodically pulls the aggregator's changed-databases feed and
// wakes the affected DatabaseSecretClaim reconciles.
//
// Correctness does not depend on the poller catching every change: the operator's
// startup full reconcile syncs all CRs against current state, and the per-CR
// safety-net requeue is the ultimate backstop. The cursor is therefore in-memory
// — on restart or leader failover the new leader re-seeds from the aggregator's
// high-water mark and the startup sweep covers the gap.
//
// The cursor is a keyset (lastRotatedAt, id) so paging is deterministic even when
// many rows share an identical timestamp (e.g. a restore stamps one timestamp
// across a database's registries) — the poller always makes forward progress.
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

// Start runs the poll loop until ctx is canceled. It implements manager.Runnable.
func (p *RotationPoller) Start(ctx context.Context) error {
	limit := p.Limit
	if limit <= 0 {
		limit = DefaultLimit
	}
	log.Infof("Starting rotation poller interval=%v limit=%d", p.Interval, limit)

	// Seed immediately, before the first tick, so the baseline is captured at
	// ~leadership acquisition — the same point the startup reconcile runs. Any
	// rotation after this point sorts strictly after the cursor and is fanned out;
	// anything before is covered by the startup reconcile. Seeding lazily on the
	// first tick would leave an interval-long window whose rotations are skipped.
	cursor := p.pollOnce(ctx, nil, limit)

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
// rotations — already synced by the startup reconcile — are not replayed. A failed
// seed returns nil so the next tick retries it.
func (p *RotationPoller) pollOnce(ctx context.Context, cursor *aggregatorclient.ChangeCursor, limit int) *aggregatorclient.ChangeCursor {
	if cursor == nil {
		resp, err := p.Source.GetChangedSince(ctx, nil, 0)
		if err != nil {
			log.ErrorC(ctx, "Rotation poller failed to seed cursor (will retry next tick): %v", err)
			return nil
		}
		if resp.HighWaterMark != nil {
			log.InfoC(ctx, "Rotation poller seeded cursor lastRotatedAt=%v id=%s",
				resp.HighWaterMark.LastRotatedAt.UTC(), resp.HighWaterMark.ID)
			return resp.HighWaterMark
		}
		c := epochCursor()
		log.InfoC(ctx, "Rotation poller seeded cursor at epoch (no rotations recorded yet)")
		return &c
	}

	resp, err := p.Source.GetChangedSince(ctx, cursor, limit)
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
		}
		// Items are ordered by (lastRotatedAt, id) ascending; advance the cursor
		// unconditionally so a per-namespace list error (logged above) does not
		// stall it — the safety-net reconcile heals any skipped fan-out.
		advanced = &aggregatorclient.ChangeCursor{LastRotatedAt: it.LastRotatedAt, ID: it.ID}
	}
	if len(resp.Items) > 0 {
		log.InfoC(ctx, "Rotation poller processed changes count=%d cursor=(%v,%s)",
			len(resp.Items), advanced.LastRotatedAt.UTC(), advanced.ID)
	}
	return advanced
}
