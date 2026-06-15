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

package webhook

import (
	"time"
)

// PathRotationNotify is the HTTP path the rotation webhook handler is
// mounted at. Aggregator-side configuration (Helm value for the callback
// URL) must point at "<operator-service-base>/<PathRotationNotify>".
const PathRotationNotify = "/api/rotation/v1/notify"

// Event type names accepted on the webhook. The handler treats both
// identically (re-fetch credentials, content-compare, write Secret if
// changed) — the distinction is preserved only for observability so
// dashboards can separate routine rotation cadence from backup-restore
// rebinds. Unknown values are accepted as well (tolerant parsing) and
// labelled "Unknown" in metrics; the handler does not enforce an enum.
const (
	EventTypeRotationOccurred = "ROTATION_OCCURRED"
	EventTypeRestoreCompleted = "RESTORE_COMPLETED"
)

// RotationEventPayload is the JSON body of a rotation notification from
// dbaas-aggregator. The schema is documented in the aggregator-side outbox
// design and mirrors what the aggregator's OperatorEventOutboxWriter emits.
//
// Parsing is intentionally tolerant — unknown fields are ignored so a
// future aggregator schema extension does not break older operators, and
// missing optional fields fall back to zero values rather than erroring
// out.
type RotationEventPayload struct {
	// EventID is the UUID assigned to this rotation event by the aggregator's
	// outbox writer. The handler stores it as the value of the
	// dbaas.netcracker.com/rotation-trigger annotation it patches on each
	// affected DatabaseSecretClaim CR, both as a correlation ID and to guarantee
	// the underlying watch fires on every event (a no-op patch — identical
	// annotation value — would not).
	EventID string `json:"eventId"`

	// OccurredAt records when the rotation was committed on the aggregator
	// side. Carried through for logs and SIEM correlation; the operator
	// does not use it for any control-flow decision.
	OccurredAt time.Time `json:"occurredAt"`

	// PreviousRotatedAt records the previous rotation timestamp for the
	// same (classifier, type, userRole), if any. Optional and purely
	// informational on the operator side.
	PreviousRotatedAt *time.Time `json:"previousRotatedAt,omitempty"`

	// Classifier is the flat-map form of the dbaas Classifier identifying
	// the affected database. The operator round-trips it through the typed
	// dbaasv1.Classifier to drop any unknown fields before computing the
	// cache index key — that way the index lookup uses the same canonical
	// shape that controller-side CRs were indexed under.
	Classifier map[string]any `json:"classifier"`

	// Type is the database engine type (e.g. "postgresql", "mongodb").
	// Part of the (classifier, type) compound key used to resolve which
	// DatabaseSecretClaim CRs the rotation affects.
	Type string `json:"type"`

	// UserRole is the database role whose password was rotated. Not used
	// by the operator for routing (the index does not include role — see
	// dbaasv1.ClassifierTypeIndex comment) but carried for logs and metric
	// labels. May be empty in degenerate cases (older aggregator versions,
	// restore events that do not bind to a specific role); the handler
	// must remain tolerant.
	UserRole string `json:"userRole,omitempty"`

	// EventType is the dispatcher's classification of why this event
	// fired — EventTypeRotationOccurred or EventTypeRestoreCompleted.
	// Optional; operators that receive an empty or unknown value treat
	// the event as a generic "credentials changed" and proceed normally.
	EventType string `json:"eventType,omitempty"`
}
