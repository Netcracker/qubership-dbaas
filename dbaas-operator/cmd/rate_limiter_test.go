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

package main

import (
	"fmt"
	"testing"
	"time"

	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/util/workqueue"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

func TestControllerOptionsHaveIndependentBackoffState(t *testing.T) {
	const (
		baseDelay = 100 * time.Millisecond
		maxDelay  = 5 * time.Second
	)
	request := reconcile.Request{}
	firstController := newControllerOptions(baseDelay, maxDelay).RateLimiter
	secondController := newControllerOptions(baseDelay, maxDelay).RateLimiter

	assertDelayBetween(t, firstController.When(request), baseDelay, 110*time.Millisecond)
	assertDelayBetween(t, firstController.When(request), 2*baseDelay, 220*time.Millisecond)

	// The same NamespacedName in another controller must start at its own base delay.
	assertDelayBetween(t, secondController.When(request), baseDelay, 110*time.Millisecond)

	// A successful reconcile in the second controller must not reset the first one.
	secondController.Forget(request)
	assertDelayBetween(t, firstController.When(request), 4*baseDelay, 440*time.Millisecond)

	firstController.Forget(request)
	assertDelayBetween(t, firstController.When(request), baseDelay, 110*time.Millisecond)
}

func TestControllerOptionsIncludeTokenBucket(t *testing.T) {
	limiter := newControllerOptions(time.Nanosecond, time.Nanosecond).RateLimiter

	var delay time.Duration
	for i := range controllerRateBurst + 1 {
		delay = limiter.When(reconcile.Request{
			NamespacedName: types.NamespacedName{Name: fmt.Sprintf("request-%d", i)},
		})
	}

	if delay <= time.Nanosecond {
		t.Fatalf("expected request beyond burst=%d to be delayed by the token bucket, got %v",
			controllerRateBurst, delay)
	}
}

func TestJitteredRateLimiterBoundsDelayAndDelegatesState(t *testing.T) {
	delegate := &fixedRateLimiter[string]{delay: 100 * time.Millisecond}
	var observedFactor float64
	limiter := &jitteredRateLimiter[string]{
		delegate: delegate,
		maxDelay: 110 * time.Millisecond,
		jitter: func(delay time.Duration, factor float64) time.Duration {
			observedFactor = factor
			return delay + 20*time.Millisecond
		},
	}

	if delay := limiter.When("item"); delay != 110*time.Millisecond {
		t.Fatalf("expected jittered delay to be capped at 110ms, got %v", delay)
	}
	if observedFactor != backoffJitterMaxFactor {
		t.Fatalf("expected jitter factor %v, got %v", backoffJitterMaxFactor, observedFactor)
	}
	if requeues := limiter.NumRequeues("item"); requeues != 1 {
		t.Fatalf("expected delegated requeue count 1, got %d", requeues)
	}

	limiter.Forget("item")
	if requeues := limiter.NumRequeues("item"); requeues != 0 {
		t.Fatalf("expected Forget to reset delegated state, got %d requeues", requeues)
	}
}

func assertDelayBetween(t *testing.T, actual, minimum, maximum time.Duration) {
	t.Helper()
	if actual < minimum || actual > maximum {
		t.Fatalf("expected delay in [%v, %v], got %v", minimum, maximum, actual)
	}
}

type fixedRateLimiter[T comparable] struct {
	delay    time.Duration
	requeues int
}

var _ workqueue.TypedRateLimiter[string] = &fixedRateLimiter[string]{}

func (r *fixedRateLimiter[T]) When(T) time.Duration {
	r.requeues++
	return r.delay
}

func (r *fixedRateLimiter[T]) Forget(T) {
	r.requeues = 0
}

func (r *fixedRateLimiter[T]) NumRequeues(T) int {
	return r.requeues
}
