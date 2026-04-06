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

// Package ownership manages namespace ownership for the dbaas-operator.
//
// The central abstraction is OwnershipResolver: a write-through in-memory cache
// that maps Kubernetes namespace names to one of three states:
//
//   - Mine    — an OperatorBinding in this namespace points to the operator's
//               own location (CLOUD_NAMESPACE), so the operator owns it.
//   - Foreign — an OperatorBinding exists but belongs to a different operator
//               instance; workload resources in this namespace must be ignored.
//   - Unknown — no OperatorBinding has been observed yet; the resolver must
//               perform a live API call to determine the true state.
//
// The cache is populated eagerly at startup (WarmupOwnershipCache) and kept
// current by the OperatorBindingReconciler, which calls SetOwner/Forget on
// every create/update/delete event.
package ownership

import (
	"context"
	"fmt"
	"sync"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

var log = logging.GetLogger("dbaas-operator.ownership")

// OwnershipState is the result of an ownership resolution for a namespace.
type OwnershipState int

const (
	// Unknown means no cached information is available; a live lookup is needed.
	Unknown OwnershipState = iota
	// Mine means the namespace is owned by this operator instance.
	Mine
	// Foreign means the namespace is claimed by a different operator instance.
	Foreign
)

// String returns a human-readable name for the state (useful in logs and tests).
func (s OwnershipState) String() string {
	switch s {
	case Mine:
		return "Mine"
	case Foreign:
		return "Foreign"
	default:
		return "Unknown"
	}
}

// OwnershipResolver is a write-through in-memory cache of namespace ownership
// states.  It is safe for concurrent use.
type OwnershipResolver struct {
	mu          sync.RWMutex
	cache       map[string]OwnershipState // key = namespace
	myNamespace string                    // CLOUD_NAMESPACE of this operator pod
	client      client.Client
}

// NewOwnershipResolver creates a resolver for an operator whose own namespace
// is myNamespace.  All OperatorBindings whose Spec.Location equals myNamespace
// will be considered Mine; all others will be Foreign.
func NewOwnershipResolver(myNamespace string, c client.Client) *OwnershipResolver {
	return &OwnershipResolver{
		cache:       make(map[string]OwnershipState),
		myNamespace: myNamespace,
		client:      c,
	}
}

// SetOwner updates the cache for namespace based on the binding's location.
// Called by OperatorBindingReconciler on every create/update reconcile.
func (r *OwnershipResolver) SetOwner(namespace, location string) {
	state := Foreign
	if location == r.myNamespace {
		state = Mine
	}
	r.mu.Lock()
	r.cache[namespace] = state
	r.mu.Unlock()
}

// Forget removes the cached entry for namespace.
// Called by OperatorBindingReconciler when an OperatorBinding is deleted.
func (r *OwnershipResolver) Forget(namespace string) {
	r.mu.Lock()
	delete(r.cache, namespace)
	r.mu.Unlock()
}

// GetState returns the cached OwnershipState for namespace without any API
// calls.  Returns Unknown when no entry exists.
func (r *OwnershipResolver) GetState(namespace string) OwnershipState {
	r.mu.RLock()
	s, ok := r.cache[namespace]
	r.mu.RUnlock()
	if !ok {
		return Unknown
	}
	return s
}

// IsMyNamespace reports whether the operator owns namespace.
//
// Fast path: if the cache has an entry, it is returned immediately.
// Slow path: if the cache is empty for this namespace the resolver fetches the
// OperatorBinding named "registration" from the API server, updates the cache,
// and returns the result.
//
// Returns (false, nil) when there is no OperatorBinding (unowned namespace).
// Returns an error only when the API call itself fails.
func (r *OwnershipResolver) IsMyNamespace(ctx context.Context, namespace string) (bool, error) {
	// Fast path.
	if state := r.GetState(namespace); state != Unknown {
		return state == Mine, nil
	}

	// Slow path — live lookup.
	ob := &dbaasv1.OperatorBinding{}
	err := r.client.Get(ctx, client.ObjectKey{Namespace: namespace, Name: dbaasv1.OperatorBindingName}, ob)
	if err != nil {
		if client.IgnoreNotFound(err) == nil {
			// No binding → namespace is not owned by anyone.
			// Do NOT cache: a binding may be created later and the reconciler
			// will populate the cache at that point.
			return false, nil
		}
		return false, fmt.Errorf("get OperatorBinding %s/%s: %w", namespace, dbaasv1.OperatorBindingName, err)
	}

	// Cache the result for subsequent fast-path hits.
	r.SetOwner(namespace, ob.Spec.Location)
	return ob.Spec.Location == r.myNamespace, nil
}

// WarmupOwnershipCache lists all OperatorBindings cluster-wide and populates
// the cache before the controller loops start.  Errors are non-fatal — the
// resolver falls back to slow-path lookups for uncached namespaces.
func (r *OwnershipResolver) WarmupOwnershipCache(ctx context.Context) error {
	list := &dbaasv1.OperatorBindingList{}
	if err := r.client.List(ctx, list); err != nil {
		return fmt.Errorf("list OperatorBindings for cache warmup: %w", err)
	}
	r.mu.Lock()
	for _, ob := range list.Items {
		state := Foreign
		if ob.Spec.Location == r.myNamespace {
			state = Mine
		}
		r.cache[ob.Namespace] = state
	}
	r.mu.Unlock()
	log.InfoC(ctx, "ownership cache warmed up with %d bindings", len(list.Items))
	return nil
}
