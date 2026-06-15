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
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxmanager"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

var log = logging.GetLogger("dbaas-rotation-webhook")

// xRequestIDHeader is the request-correlation HTTP header used across the
// platform. The handler propagates an incoming value when present so the
// operator's logs share a request ID with dbaas-aggregator's outbox dispatch
// logs; otherwise it generates a fresh UUID.
const xRequestIDHeader = "X-Request-Id"

// classifierNamespaceKey is the key inside the aggregator's flat-map
// classifier that carries the Kubernetes namespace of the affected database.
// The aggregator always populates it (isValidClassifierV3 requires it on the
// way in), so the handler treats absence as a malformed payload.
const classifierNamespaceKey = "namespace"

// RotationHandler is the operator-side receiver for rotation events emitted
// by dbaas-aggregator's outbox dispatcher. The handler authenticates the
// caller, looks up every DatabaseSecretClaim CR matching (classifier, type) via
// the cache field index, and patches each match with a fresh
// dbaasv1.AnnotationRotationTrigger value. A controller-side predicate added
// in a later commit will react to the annotation change and reconcile the
// secret content; this handler does not touch Reconcile directly so that the
// HTTP receiver remains thin and the reconcile path stays leader-bound.
type RotationHandler struct {
	// Client is the controller-runtime client used to list affected
	// DatabaseSecretClaim CRs and to patch annotations on them. Must be backed
	// by an informer cache that has the ClassifierTypeIndex registered.
	Client client.Client

	// Auth validates the inbound Authorization header. Typically the
	// production Kubernetes-OIDC implementation from NewKubernetesAuthenticator;
	// tests may inject a stub.
	Auth Authenticator

	// AllowedSubjects is the set of Kubernetes subjects
	// (system:serviceaccount:<namespace>:<serviceAccount>) permitted to invoke
	// the webhook. A request whose authenticated subject is absent from this
	// set is rejected with 403, even though its token passed audience
	// validation — the audience check proves the token is valid and minted for
	// this operator, not *who* the caller is.
	//
	// The set is fail-closed: an empty or nil set denies every caller. main.go
	// always populates it via ParseAllowedSubjects, which supplies a derived
	// default when no explicit allow-list is configured, so the webhook is
	// never left open by omission.
	AllowedSubjects map[string]struct{}
}

// rotationResponse is returned as JSON on a successful (200 OK) call. It
// reports how many CRs matched the event and how many were successfully
// patched. The two counts may diverge when k8s API patches fail for
// individual CRs (NotFound, conflict, etc.); the handler logs each failure
// but does not surface details to the caller — the aggregator's contract is
// "operator accepted the notification", not "operator finished propagating".
type rotationResponse struct {
	Matched int `json:"matched"`
	Patched int `json:"patched"`
}

// ServeHTTP implements http.Handler.
func (h *RotationHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 1. Bind a request ID into the context so subsequent logs are correlated.
	ctx := h.bindRequestID(r)

	// 2. Method check — only POST is meaningful.
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// 3. Authenticate the caller.
	authResult, err := h.Auth.Authenticate(ctx, r.Header.Get("Authorization"))
	if err != nil {
		log.InfoC(ctx, "Rotation webhook rejected unauthenticated request: %v", err)
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	// 4. Authorize the caller. A valid, audience-scoped token proves the
	// request came from a pod that opted in, but not that this specific
	// caller is permitted to trigger rotations. Reject any subject outside
	// the configured allow-list with 403 before touching the payload.
	if !h.subjectAllowed(authResult.Subject) {
		log.InfoC(ctx, "Rotation webhook rejected unauthorized caller subject=%s", authResult.Subject)
		writeError(w, http.StatusForbidden, "forbidden")
		return
	}

	// 5. Decode and validate payload.
	payload, err := decodePayload(r)
	if err != nil {
		log.InfoC(ctx, "Rotation webhook rejected malformed payload subject=%s err=%v",
			authResult.Subject, err)
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	// 6. Resolve affected CRs and patch the rotation-trigger annotation.
	matched, patched, err := h.processEvent(ctx, payload)
	if err != nil {
		log.ErrorC(ctx, "Rotation webhook failed to process event eventId=%s subject=%s err=%v",
			payload.EventID, authResult.Subject, err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	log.InfoC(ctx, "Rotation webhook processed event eventId=%s eventType=%s type=%s userRole=%s subject=%s matched=%d patched=%d",
		payload.EventID, eventTypeForLog(payload.EventType), payload.Type, payload.UserRole,
		authResult.Subject, matched, patched)

	writeJSON(w, http.StatusOK, rotationResponse{Matched: matched, Patched: patched})
}

// subjectAllowed reports whether subject is in the configured allow-list. A
// nil or empty set denies every caller (fail-closed).
func (h *RotationHandler) subjectAllowed(subject string) bool {
	_, ok := h.AllowedSubjects[subject]
	return ok
}

// bindRequestID copies an incoming X-Request-Id header into the request
// context, falling back to a freshly generated UUID. Without this any logs
// emitted inside the handler would be orphaned from the aggregator's outbox
// dispatch logs.
func (h *RotationHandler) bindRequestID(r *http.Request) context.Context {
	requestID := r.Header.Get(xRequestIDHeader)
	if requestID == "" {
		requestID = uuid.New().String()
	}
	return ctxmanager.InitContext(r.Context(), map[string]any{xRequestIDHeader: requestID})
}

// decodePayload parses the request body and verifies the minimum set of
// fields the handler needs to act. Unknown fields are accepted (tolerant
// parsing) so future schema extensions on the aggregator do not break older
// operators.
func decodePayload(r *http.Request) (RotationEventPayload, error) {
	var payload RotationEventPayload
	if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
		return RotationEventPayload{}, fmt.Errorf("invalid JSON: %w", err)
	}
	if payload.EventID == "" {
		return RotationEventPayload{}, errors.New("eventId is required")
	}
	if payload.Type == "" {
		return RotationEventPayload{}, errors.New("type is required")
	}
	if len(payload.Classifier) == 0 {
		return RotationEventPayload{}, errors.New("classifier is required")
	}
	if _, ok := payload.Classifier[classifierNamespaceKey].(string); !ok {
		return RotationEventPayload{}, errors.New("classifier.namespace is required")
	}
	return payload, nil
}

// processEvent does the cache lookup + annotation patches.
// Per-CR patch failures are logged but do NOT abort the loop: aggregator
// retries are expensive and a single transient k8s API error for one CR
// should not block notifications for the rest.
func (h *RotationHandler) processEvent(ctx context.Context, payload RotationEventPayload) (matched, patched int, err error) {
	typed, err := classifierFromMap(payload.Classifier)
	if err != nil {
		return 0, 0, fmt.Errorf("normalize classifier: %w", err)
	}
	indexKey := dbaasv1.ClassifierIndexKey(typed, payload.Type)
	namespace, _ := payload.Classifier[classifierNamespaceKey].(string)

	list := &dbaasv1.DatabaseSecretClaimList{}
	if err := h.Client.List(ctx, list,
		client.InNamespace(namespace),
		client.MatchingFields{dbaasv1.ClassifierTypeIndex: indexKey}); err != nil {
		return 0, 0, fmt.Errorf("list DatabaseSecretClaim in %s: %w", namespace, err)
	}
	matched = len(list.Items)

	patchBytes, err := json.Marshal(map[string]any{
		"metadata": map[string]any{
			"annotations": map[string]string{
				dbaasv1.AnnotationRotationTrigger: payload.EventID,
			},
		},
	})
	if err != nil {
		// Unreachable for our hand-built static map.
		return matched, 0, fmt.Errorf("build patch body: %w", err)
	}

	for i := range list.Items {
		ds := &list.Items[i]
		if patchErr := h.Client.Patch(ctx, ds, client.RawPatch(types.MergePatchType, patchBytes)); patchErr != nil {
			log.ErrorC(ctx, "Rotation webhook failed to patch annotation name=%s namespace=%s err=%v",
				ds.Name, ds.Namespace, patchErr)
			continue
		}
		patched++
	}
	return matched, patched, nil
}

// classifierFromMap round-trips the aggregator's flat-map classifier through
// the typed dbaasv1.Classifier so any unknown fields the aggregator may add
// in the future are dropped before we compute the index key. The result is
// guaranteed to be canonicalised the same way controller-side CRs were
// indexed under.
func classifierFromMap(m map[string]any) (dbaasv1.Classifier, error) {
	raw, err := json.Marshal(m)
	if err != nil {
		return dbaasv1.Classifier{}, fmt.Errorf("marshal classifier: %w", err)
	}
	var c dbaasv1.Classifier
	if err := json.Unmarshal(raw, &c); err != nil {
		return dbaasv1.Classifier{}, fmt.Errorf("unmarshal classifier: %w", err)
	}
	return c, nil
}

// eventTypeForLog returns a non-empty event type label for log lines,
// distinguishing genuinely-empty incoming values from the known ones.
func eventTypeForLog(t string) string {
	if t == "" {
		return "Unknown"
	}
	return t
}

func writeError(w http.ResponseWriter, status int, msg string) {
	http.Error(w, msg, status)
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
