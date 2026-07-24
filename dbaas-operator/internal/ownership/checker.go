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

package ownership

import (
	"context"
	"fmt"

	apimeta "k8s.io/apimachinery/pkg/api/meta"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// BlockingResourceChecker reports which resource kinds in a namespace must be
// removed before a NamespaceBinding can be safely deleted. An empty result
// means nothing blocks the deletion. Returning the kind names (instead of a
// bare bool) lets the caller tell the user what exactly is in the way — in the
// BindingBlocked event and in the Ready condition message.
type BlockingResourceChecker interface {
	BlockingKinds(ctx context.Context, namespace string) ([]string, error)
}

// KindChecker checks for the presence of any object of a specific list kind
// within a namespace.  Use the generic constructor NewKindChecker so that the
// type constraints are verified at compile time.
type KindChecker[L client.ObjectList] struct {
	cl      client.Client
	kind    string
	newList func() L
}

// NewKindChecker creates a KindChecker for the given list type.
//
//   - kind is the resource kind name reported when objects are found
//     (e.g. "ExternalDatabase")
//   - newList returns a fresh, empty list object (e.g. func() *dbaasv1.ExternalDatabaseList { return &dbaasv1.ExternalDatabaseList{} })
//
// The item count is obtained generically via meta.LenList, so no per-type
// counting closure is required.
func NewKindChecker[L client.ObjectList](cl client.Client, kind string, newList func() L) *KindChecker[L] {
	return &KindChecker[L]{cl: cl, kind: kind, newList: newList}
}

// BlockingKinds returns [kind] when at least one object of this kind exists in
// namespace, and nil otherwise. The list is capped at one item — presence is
// enough, counting would need an uncapped LIST.
func (c *KindChecker[L]) BlockingKinds(ctx context.Context, namespace string) ([]string, error) {
	list := c.newList()
	log.InfoC(ctx, "Checking blocking resources kind=%s namespace=%s", c.kind, namespace)
	if err := c.cl.List(ctx, list, client.InNamespace(namespace), client.Limit(1)); err != nil {
		return nil, fmt.Errorf("list %T in namespace %q: %w", list, namespace, err)
	}
	found := apimeta.LenList(list) > 0
	log.InfoC(ctx, "Checked blocking resources kind=%s namespace=%s found=%v", c.kind, namespace, found)
	if !found {
		return nil, nil
	}
	return []string{c.kind}, nil
}

// CompositeChecker aggregates multiple BlockingResourceCheckers, concatenating
// the kinds each of them reports.
type CompositeChecker struct {
	checkers []BlockingResourceChecker
}

// NewCompositeChecker creates a CompositeChecker with the given initial set of checkers.
func NewCompositeChecker(checkers ...BlockingResourceChecker) *CompositeChecker {
	return &CompositeChecker{checkers: checkers}
}

// Add appends a checker to the composite.
func (c *CompositeChecker) Add(ch BlockingResourceChecker) {
	c.checkers = append(c.checkers, ch)
}

// BlockingKinds runs every constituent checker and returns the union of the
// kinds they report, in registration order. Unlike a short-circuiting bool
// check, the full sweep costs one Limit(1) LIST per kind and buys a complete
// answer for the user-facing message.
func (c *CompositeChecker) BlockingKinds(ctx context.Context, namespace string) ([]string, error) {
	log.InfoC(ctx, "Checking blocking resources namespace=%s checkers=%d", namespace, len(c.checkers))
	var kinds []string
	for _, ch := range c.checkers {
		blocking, err := ch.BlockingKinds(ctx, namespace)
		if err != nil {
			return nil, err
		}
		kinds = append(kinds, blocking...)
	}
	log.InfoC(ctx, "Checked blocking resources namespace=%s kinds=%v", namespace, kinds)
	return kinds, nil
}
