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
	"encoding/json"
)

// ClassifierTypeIndex is the field-index key used by the operator's caching
// client to look up DatabaseSecretClaim CRs by the canonical (spec.classifier,
// spec.type) pair. Both the controller's SetupWithManager and the rotation
// webhook receiver register and query this index — keeping the key name in
// the API package guarantees they stay in sync.
//
// The index intentionally excludes spec.userRole. The aggregator resolves
// userRole through DatabaseAccessPolicy (defaultRole, additionalRole) and the global
// permission registry, so the operator cannot reliably map its local
// spec.userRole to the aggregator's effective role without replicating that
// resolution. The webhook handler fans out to every DatabaseSecretClaim matching
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
	m := make(map[string]any, 4+len(c.CustomKeys))
	m["microserviceName"] = c.MicroserviceName
	m["scope"] = c.Scope
	if c.Namespace != "" {
		m["namespace"] = c.Namespace
	}
	if c.TenantId != "" {
		m["tenantId"] = c.TenantId
	}
	if len(c.CustomKeys) > 0 {
		customKeys := make(map[string]any, len(c.CustomKeys))
		for k, v := range c.CustomKeys {
			var val any
			if err := json.Unmarshal(v.Raw, &val); err != nil {
				// raw bytes are always valid JSON from the API server, but
				// fall back to string representation if something goes wrong.
				customKeys[k] = string(v.Raw)
				continue
			}
			customKeys[k] = val
		}
		m["customKeys"] = customKeys
	}
	return m
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
