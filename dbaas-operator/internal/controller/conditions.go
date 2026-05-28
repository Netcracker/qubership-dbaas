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

// ownershipPollInterval is the requeue delay used when a workload CR is
// reconciled in a namespace whose NamespaceBinding state is Unknown (no cache
// entry at all — transient window at startup or right after Forget).  The
// short interval is acceptable because Unknown is genuinely ephemeral.
const ownershipPollInterval = 30 * time.Second

// ownershipUnboundRetryInterval is the requeue delay used when a workload CR
// is reconciled in a namespace confirmed to have no NamespaceBinding (Unbound
// state).  It is intentionally much longer than ownershipPollInterval because
// Unbound namespaces are common (any namespace without a binding) and polling
// them every 30 s would cause background churn.
//
// This interval acts as a safety net: if the NamespaceBinding → workloads
// fan-out watch fires but the LIST inside the MapFunc fails transiently, the
// cached state transitions from Unbound → Mine (SetOwner is still called by
// the NamespaceBindingReconciler), and the periodic requeue here guarantees the
// workload CR will eventually be reconciled even if that watch trigger was lost.
const ownershipUnboundRetryInterval = 5 * time.Minute

// secretNamesIndex is the field index key used to look up ExternalDatabases
// by the namespace-qualified name of a secret they reference in credentialsSecretRef.
// The key format is "namespace/name" to prevent spurious cross-namespace reconciles
// when two namespaces share a secret with the same name.
const secretNamesIndex = "spec.credentialSecretNames"

// databaseNotFoundTimeout is the duration after which a DatabaseSecret that has
// been continuously receiving DatabaseNotFound (404) responses from the aggregator
// is considered stuck. Polling continues (so the CR can recover if the database
// eventually appears), but the controller switches to EventReasonDatabaseNotFoundTimeout
// and stops the per-cycle Warning event spam. Surfacing the timeout as a one-shot
// Warning gives operators a single, alertable signal.
const databaseNotFoundTimeout = 10 * time.Minute
