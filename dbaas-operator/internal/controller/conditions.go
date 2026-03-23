package controller

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
