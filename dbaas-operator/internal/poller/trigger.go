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
			log.ErrorC(ctx, "Failed to patch rotation-trigger annotation name=%s namespace=%s err=%v",
				ds.Name, ds.Namespace, patchErr)
			continue
		}
		patched++
	}
	return matched, patched, nil
}

// classifierFromMap round-trips the aggregator's flat-map classifier through the
// typed dbaasv1.Classifier so unknown fields are dropped before computing the
// index key, guaranteeing the same canonical key the controller indexed CRs under.
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
