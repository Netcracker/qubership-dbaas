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
