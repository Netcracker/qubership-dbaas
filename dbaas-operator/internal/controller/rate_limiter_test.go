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

package controller

import (
	"strconv"
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/util/workqueue"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

func TestRateLimiterConfigCreatesIndependentBackoffState(t *testing.T) {
	const (
		baseDelay = 100 * time.Millisecond
		maxDelay  = 5 * time.Second
	)
	request := reconcile.Request{}
	config := NewRateLimiterConfig(baseDelay, maxDelay)
	firstController := config.controllerOptions().RateLimiter
	secondController := config.controllerOptions().RateLimiter

	assertDelayBetween(t, firstController.When(request), baseDelay, 110*time.Millisecond)
	assertDelayBetween(t, firstController.When(request), 2*baseDelay, 220*time.Millisecond)

	// The same NamespacedName in another controller starts at its own base delay.
	assertDelayBetween(t, secondController.When(request), baseDelay, 110*time.Millisecond)

	// A successful reconcile in the second controller does not reset the first one.
	secondController.Forget(request)
	assertDelayBetween(t, firstController.When(request), 4*baseDelay, 440*time.Millisecond)

	firstController.Forget(request)
	assertDelayBetween(t, firstController.When(request), baseDelay, 110*time.Millisecond)
}

func TestRateLimiterDoesNotThrottleUnrelatedItems(t *testing.T) {
	const maxDelay = time.Nanosecond
	limiter := NewRateLimiterConfig(maxDelay, maxDelay).controllerOptions().RateLimiter

	for i := range 1000 {
		delay := limiter.When(reconcile.Request{
			NamespacedName: types.NamespacedName{Name: strconv.Itoa(i)},
		})
		if delay > maxDelay {
			t.Fatalf("expected item %d delay not to exceed %v, got %v", i, maxDelay, delay)
		}
	}
}

func TestJitteredRateLimiterBoundsAndDelegatesState(t *testing.T) {
	const delay = 100 * time.Millisecond
	request := reconcile.Request{}
	delegate := workqueue.NewTypedItemFastSlowRateLimiter[reconcile.Request](delay, delay, 1)
	limiter := &jitteredRateLimiter{delegate: delegate, maxDelay: 110 * time.Millisecond}

	assertDelayBetween(t, limiter.When(request), delay, 110*time.Millisecond)
	if requeues := limiter.NumRequeues(request); requeues != 1 {
		t.Fatalf("expected delegated requeue count 1, got %d", requeues)
	}

	limiter.Forget(request)
	if requeues := limiter.NumRequeues(request); requeues != 0 {
		t.Fatalf("expected Forget to reset delegated state, got %d requeues", requeues)
	}
}

func TestJitteredRateLimiterSpreadsRetriesAtMaximumDelay(t *testing.T) {
	const maxDelay = 100 * time.Millisecond
	request := reconcile.Request{}
	delegate := workqueue.NewTypedItemFastSlowRateLimiter[reconcile.Request](maxDelay, maxDelay, 1)
	limiter := &jitteredRateLimiter{delegate: delegate, maxDelay: maxDelay}

	for range 100 {
		assertDelayBetween(t, limiter.When(request), 90*time.Millisecond, maxDelay)
		limiter.Forget(request)
	}
}

func assertDelayBetween(t *testing.T, actual, minimum, maximum time.Duration) {
	t.Helper()
	if actual < minimum || actual > maximum {
		t.Fatalf("expected delay in [%v, %v], got %v", minimum, maximum, actual)
	}
}
