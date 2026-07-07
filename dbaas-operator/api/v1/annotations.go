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

// AnnotationRotationTrigger is the annotation key that the rotation poller
// writes on a DatabaseSecretClaim CR to signal that the aggregator reported a
// credentials change for the CR's (classifier, type). The annotation's value is
// the change's lastRotatedAt timestamp; storing a fresh value on each change
// guarantees the underlying Kubernetes watch fires (an identical patch would be
// a no-op).
//
// Producer: the rotation poller in internal/poller.
// Consumer: a controller-side predicate that fires reconcile on annotation
// changes, in addition to the standard generation-change predicate.
const AnnotationRotationTrigger = "dbaas.netcracker.com/rotation-trigger"

// AnnotationRefresh forces an immediate reconcile of an ExternalDatabase. The
// ExternalDatabase controller does not watch Secrets (to avoid cluster-wide
// Secret list/watch RBAC), so a change to a referenced credential Secret is
// otherwise only picked up on the periodic resync (RequeueAfter). Changing this
// annotation's value (e.g. to a fresh timestamp) makes the underlying Kubernetes
// watch fire, triggering an immediate re-read of the referenced Secret and a
// re-register with dbaas-aggregator. The value is opaque; only a change matters
// (an identical patch is a no-op), so callers should write a changing value:
//
//	kubectl annotate externaldatabase <name> dbaas.netcracker.com/refresh="$(date +%s)" --overwrite
//
// Producer: an operator/admin (manual or automation), not the operator itself.
// Consumer: a controller-side predicate that fires reconcile on annotation
// changes, in addition to the standard generation-change predicate.
const AnnotationRefresh = "dbaas.netcracker.com/refresh"
