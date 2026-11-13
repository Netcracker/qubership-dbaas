package controller

import "time"

const (
	// conditionTypeReady is the canonical condition describing whether the
	// current generation was successfully processed by the controller.
	// Ready=True  — the resource has been accepted and is active.
	// Ready=False — processing failed; check Reason and Message for details.
	conditionTypeReady = "Ready"

	// conditionTypeStalled is set to True when the error is permanent and
	// retrying will not help until the spec is changed.
	// Stalled=False — error is transient; the controller will retry automatically.
	conditionTypeStalled = "Stalled"
)

const (
	stalledMsgPermanent = "Permanent error — spec must be corrected before the controller will retry."
	stalledMsgTransient = "Transient error — the controller will retry automatically."
)

// databaseNotFoundTimeout is the duration after which a DatabaseSecretClaim that has
// been continuously receiving DatabaseNotFound (404) responses from the aggregator
// is considered stuck. Polling continues (so the CR can recover if the database
// eventually appears), but the controller switches to EventReasonDatabaseNotFoundTimeout
// and stops the per-cycle Warning event spam. Surfacing the timeout as a one-shot
// Warning gives operators a single, alertable signal.
const databaseNotFoundTimeout = 10 * time.Minute

// physicalDatabaseIDConflictTimeout is the duration after which a PhysicalDatabase
// that has been continuously receiving the aggregator's physical database id conflict
// (409, CORE-DBAAS-4011) is considered stuck. The conflict then needs a change
// outside the resource, so the controller switches to
// EventReasonPhysicalDatabaseIDConflictTimeout, records one Warning event, and
// polls at ResyncInterval instead of the poll backoff.
//
// The value is longer than databaseNotFoundTimeout because a conflict can last for
// a whole rollout: the adapter reports a new id before the aggregator drops the old
// registration. A rollout of an adapter and an operator routinely takes more than
// ten minutes, and the one-minute poll of the poll backoff keeps recovery prompt
// until then.
const physicalDatabaseIDConflictTimeout = 30 * time.Minute

// secretRotationSafetyNetInterval is the requeue delay applied after a
// successful DatabaseSecretClaim reconcile. The rotation poller is the primary
// trigger for credential updates; this slow periodic re-poll is a
// safety net that recovers from missed rotation events (operator restart,
// network partition that outlasts the aggregator's retry budget, or a
// rotation that slips past the poller's cursor entirely). Each cycle re-fetches the
// credentials and the content-aware compare suppresses the write when nothing
// changed, so an idle CR costs one aggregator round-trip per interval and no
// Secret churn. One hour keeps the aggregator load negligible (≈ #CRs per hour)
// while bounding the worst-case staleness for a dropped event.
const secretRotationSafetyNetInterval = 1 * time.Hour
