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

package v1

import (
	"bytes"
	"encoding/json"
	"sort"

	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
)

// ClassifierTypeIndex is the field-index key used by the operator's caching
// client to look up DatabaseSecretClaim CRs by the canonical (spec.classifier,
// spec.type) pair. Both the controller's SetupWithManager and the rotation
// poller register and query this index — keeping the key name in
// the API package guarantees they stay in sync.
//
// The index intentionally excludes spec.userRole. The aggregator resolves
// userRole through DatabaseAccessPolicy (defaultRole, additionalRole) and the global
// permission registry, so the operator cannot reliably map its local
// spec.userRole to the aggregator's effective role without replicating that
// resolution. The rotation poller fans out to every DatabaseSecretClaim matching
// the classifier+type, and the content-aware compare in Reconcile guards
// against unnecessary Secret writes — the cost is bounded since typical
// deployments have 1-3 CRs per classifier.
const ClassifierTypeIndex = "spec.classifier+type"

// EffectiveClassifier returns c with its namespace defaulted to fallbackNamespace
// (the owning CR's metadata.namespace) when c.Namespace is empty.
//
// dbaas-aggregator requires classifier.namespace (its isValidClassifierV3 rejects
// a classifier without it), and the controllers validate that a non-empty
// classifier.namespace equals the CR's metadata.namespace — so defaulting an
// omitted namespace to metadata.namespace is always the correct, unambiguous
// value. Callers must apply this before serializing the classifier for an
// aggregator request or computing its cache index key, otherwise a CR that omits
// the optional spec.classifier.namespace would be rejected by the aggregator and
// would index under a key that the (always-namespaced) rotation payload cannot
// match.
func EffectiveClassifier(c Classifier, fallbackNamespace string) Classifier {
	if c.Namespace == "" {
		c.Namespace = fallbackNamespace
	}
	return c
}

// ClassifierFlatMap converts a Classifier into the flat map shape expected on
// the wire by dbaas-aggregator (Map<String, Object>). All scalar fields are
// added as top-level keys; customKeys is added as a nested map[string]any
// under the "customKeys" key, with each JSON value deserialized. Empty
// optional fields (namespace, tenantId, customKeys) are omitted entirely so
// the resulting map matches what the aggregator stores for an equivalent
// CR — critical for ClassifierIndexKey to produce the same string on both
// sides of a rotation event.
func ClassifierFlatMap(c Classifier) map[string]any {
	m := make(map[string]any, 4+len(c.CustomKeys)+len(c.ExtraKeys))
	m["microserviceName"] = c.MicroserviceName
	m["scope"] = c.Scope
	if c.Namespace != "" {
		m["namespace"] = c.Namespace
	}
	if c.TenantID != "" {
		m["tenantId"] = c.TenantID
	}
	if len(c.CustomKeys) > 0 {
		customKeys := make(map[string]any, len(c.CustomKeys))
		for k, v := range c.CustomKeys {
			customKeys[k] = decodeClassifierValue(v)
		}
		m["customKeys"] = customKeys
	}
	// extraKeys are flattened onto the top level (legacy open-classifier
	// compatibility). Reserved keys are skipped defensively — CRD CEL
	// validation already rejects them at admission, and the typed fields above
	// always win so a stray reserved extraKey can never corrupt identity.
	for k, v := range c.ExtraKeys {
		if _, reserved := reservedClassifierKeys[k]; reserved {
			continue
		}
		m[k] = decodeClassifierValue(v)
	}
	return m
}

// reservedClassifierKeys are the top-level keys owned by the typed Classifier
// fields. extraKeys may not shadow them (enforced by CRD CEL validation; the
// check in ClassifierFlatMap is a defensive backstop for objects that bypass
// admission).
var reservedClassifierKeys = map[string]struct{}{
	"microserviceName": {},
	"scope":            {},
	"namespace":        {},
	"tenantId":         {},
	"customKeys":       {},
}

// ReservedExtraKeys returns, sorted, any extraKeys entries whose names collide
// with the typed classifier fields. ClassifierFlatMap already ignores such
// entries (the typed field wins), but a collision is always a spec mistake —
// controllers call this during pre-flight validation to reject the CR with a
// clear InvalidConfiguration instead of silently dropping the key. Returns nil
// when extraKeys is empty or contains no reserved names.
func ReservedExtraKeys(c Classifier) []string {
	var bad []string
	for k := range c.ExtraKeys {
		if _, reserved := reservedClassifierKeys[k]; reserved {
			bad = append(bad, k)
		}
	}
	sort.Strings(bad)
	return bad
}

// decodeClassifierValue deserializes a raw JSON classifier value into its
// natural Go form (string/number/bool/map/slice). Numbers are decoded as
// json.Number (via UseNumber) rather than float64 so large integers
// (> 2^53, e.g. a snowflake-style id used as an identity key) keep their exact
// literal when the flat map is re-marshaled for the aggregator request, the
// mounted-Secret descriptor, and the index key — a float64 round-trip would
// silently alter the value and the database identity. Object keys are still
// canonicalized (sorted) by the eventual json.Marshal of the map. Raw bytes from
// the API server are always valid JSON, so the string fallback is only defensive.
func decodeClassifierValue(v apiextensionsv1.JSON) any {
	dec := json.NewDecoder(bytes.NewReader(v.Raw))
	dec.UseNumber()
	var val any
	if err := dec.Decode(&val); err != nil {
		return string(v.Raw)
	}
	return val
}

// ClassifierIndexKey canonicalizes (classifier, type) into a deterministic
// string suitable for cache field-index lookup. The canonical form is
// "<type>|<json-of-ClassifierFlatMap>", where json.Marshal on map[string]any
// guarantees alphabetical key ordering (including nested customKeys). Two
// CRs with the same classifier content produce the same key regardless of
// how their JSON was originally ordered.
func ClassifierIndexKey(c Classifier, dbType string) string {
	flat := ClassifierFlatMap(c)
	raw, err := json.Marshal(flat)
	if err != nil {
		// Unreachable: ClassifierFlatMap only produces JSON-serializable
		// values (string/number/bool/nested map). Fall back to a
		// deterministic-on-content key so the index entry stays defined.
		return dbType + "|"
	}
	return dbType + "|" + string(raw)
}
