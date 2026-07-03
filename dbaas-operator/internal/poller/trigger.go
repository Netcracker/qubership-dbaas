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

// Package poller pulls rotation events from dbaas-aggregator and wakes the
// affected DatabaseSecretClaim reconciles. It replaces the former inbound
// rotation webhook: instead of the aggregator pushing notifications, the
// operator's leader polls the aggregator's changed-databases feed.
package poller

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/logfields"
	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

var log = logging.GetLogger("dbaas-rotation-poller")

// PatchClaimsForRotation lists every DatabaseSecretClaim matching (classifier,
// dbType) in the given namespace via the ClassifierTypeIndex and patches each
// with triggerValue under AnnotationRotationTrigger, waking its reconcile. A
// fresh triggerValue (the event's lastRotatedAt) guarantees the annotation
// changes so the controller predicate fires even when the CR is otherwise
// unchanged.
//
// Per-CR patch failures are logged and skipped, not propagated: one transient
// k8s API error must not block notifications for the rest, and the safety-net
// reconcile will heal any CR missed here.
func PatchClaimsForRotation(ctx context.Context, c client.Client, namespace string, classifier map[string]any, dbType, triggerValue string) (matched, patched int, err error) {
	typed, err := classifierFromMap(classifier)
	if err != nil {
		return 0, 0, fmt.Errorf("normalize classifier: %w", err)
	}
	indexKey := dbaasv1.ClassifierIndexKey(typed, dbType)

	list := &dbaasv1.DatabaseSecretClaimList{}
	if err := c.List(ctx, list,
		client.InNamespace(namespace),
		client.MatchingFields{dbaasv1.ClassifierTypeIndex: indexKey}); err != nil {
		return 0, 0, fmt.Errorf("list DatabaseSecretClaim in %s: %w", namespace, err)
	}
	matched = len(list.Items)

	patchBytes, err := json.Marshal(map[string]any{
		"metadata": map[string]any{
			"annotations": map[string]string{
				dbaasv1.AnnotationRotationTrigger: triggerValue,
			},
		},
	})
	if err != nil {
		// Unreachable for our hand-built static map.
		return matched, 0, fmt.Errorf("build patch body: %w", err)
	}

	for i := range list.Items {
		ds := &list.Items[i]
		if patchErr := c.Patch(ctx, ds, client.RawPatch(types.MergePatchType, patchBytes)); patchErr != nil {
			log.ErrorC(ctx, "%s", logfields.Format("Failed to patch rotation-trigger annotation",
				"name", ds.Name, "namespace", ds.Namespace, "error", patchErr))
			continue
		}
		patched++
	}
	return matched, patched, nil
}

// classifierFromMap reconstructs the typed dbaasv1.Classifier from the
// aggregator's flat-map classifier so that ClassifierFlatMap round-trips it back
// to the same wire shape — and therefore the same index key — the controller
// stored each DatabaseSecretClaim under.
//
// It decodes ONLY the known wire fields explicitly: the identity scalars and the
// nested "customKeys" envelope. Every *other* top-level key is an arbitrary
// identity field (spec.classifier.extraKeys, flattened onto the wire) and is
// routed into ExtraKeys. This is deliberately not a json.Unmarshal into the
// struct: a flattened extraKey literally named "extraKeys" would otherwise be
// decoded against the ExtraKeys map field and a scalar/array value would fail,
// aborting the whole rotation event. Routing every unknown key into ExtraKeys
// also guarantees the reverse round-trip reproduces the controller's index key —
// dropping an extra field would hash to a different key and silently miss the CR.
func classifierFromMap(m map[string]any) (dbaasv1.Classifier, error) {
	var c dbaasv1.Classifier
	for k, v := range m {
		switch k {
		case "microserviceName":
			c.MicroserviceName, _ = v.(string)
		case "scope":
			c.Scope, _ = v.(string)
		case "namespace":
			c.Namespace, _ = v.(string)
		case "tenantId":
			c.TenantId, _ = v.(string)
		case "customKeys":
			obj, ok := v.(map[string]any)
			if !ok {
				return dbaasv1.Classifier{}, fmt.Errorf("classifier.customKeys is %T, want a JSON object", v)
			}
			c.CustomKeys = make(map[string]apiextensionsv1.JSON, len(obj))
			for ck, cv := range obj {
				vb, err := json.Marshal(cv)
				if err != nil {
					return dbaasv1.Classifier{}, fmt.Errorf("marshal customKey %q: %w", ck, err)
				}
				c.CustomKeys[ck] = apiextensionsv1.JSON{Raw: vb}
			}
		default:
			vb, err := json.Marshal(v)
			if err != nil {
				return dbaasv1.Classifier{}, fmt.Errorf("marshal extraKey %q: %w", k, err)
			}
			if c.ExtraKeys == nil {
				c.ExtraKeys = make(map[string]apiextensionsv1.JSON)
			}
			c.ExtraKeys[k] = apiextensionsv1.JSON{Raw: vb}
		}
	}
	return c, nil
}
