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

// BlockingResourceChecker reports whether a namespace contains resources that
// must be removed before an NamespaceBinding can be safely deleted.
type BlockingResourceChecker interface {
	HasBlockingResources(ctx context.Context, namespace string) (bool, error)
}

// KindChecker checks for the presence of any object of a specific list kind
// within a namespace.  Use the generic constructor NewKindChecker so that the
// type constraints are verified at compile time.
type KindChecker[L client.ObjectList] struct {
	cl      client.Client
	newList func() L
}

// NewKindChecker creates a KindChecker for the given list type.
//
//   - newList returns a fresh, empty list object (e.g. func() *dbaasv1.ExternalDatabaseList { return &dbaasv1.ExternalDatabaseList{} })
//
// The item count is obtained generically via meta.LenList, so no per-type
// counting closure is required.
func NewKindChecker[L client.ObjectList](cl client.Client, newList func() L) *KindChecker[L] {
	return &KindChecker[L]{cl: cl, newList: newList}
}

// HasBlockingResources returns true when at least one object of this kind
// exists in namespace.
func (c *KindChecker[L]) HasBlockingResources(ctx context.Context, namespace string) (bool, error) {
	list := c.newList()
	log.InfoC(ctx, "Checking blocking resources kind=%T namespace=%s", list, namespace)
	if err := c.cl.List(ctx, list, client.InNamespace(namespace), client.Limit(1)); err != nil {
		return false, fmt.Errorf("list %T in namespace %q: %w", list, namespace, err)
	}
	found := apimeta.LenList(list) > 0
	log.InfoC(ctx, "Checked blocking resources kind=%T namespace=%s found=%v", list, namespace, found)
	return found, nil
}

// CompositeChecker aggregates multiple BlockingResourceCheckers with OR
// semantics: it returns true as soon as any checker reports a blocking resource.
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

// HasBlockingResources returns true as soon as any constituent checker finds a
// blocking resource, short-circuiting the remaining checks.
func (c *CompositeChecker) HasBlockingResources(ctx context.Context, namespace string) (bool, error) {
	log.InfoC(ctx, "Checking blocking resources namespace=%s checkers=%d", namespace, len(c.checkers))
	for _, ch := range c.checkers {
		blocking, err := ch.HasBlockingResources(ctx, namespace)
		if err != nil {
			return false, err
		}
		if blocking {
			log.InfoC(ctx, "Found blocking resources namespace=%s", namespace)
			return true, nil
		}
	}
	log.InfoC(ctx, "No blocking resources found namespace=%s", namespace)
	return false, nil
}
