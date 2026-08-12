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
	"time"

	"golang.org/x/time/rate"
	"k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/client-go/util/workqueue"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const (
	controllerRateLimit    = rate.Limit(10)
	controllerRateBurst    = 100
	backoffJitterMaxFactor = 0.1
)

type jitteredRateLimiter[T comparable] struct {
	delegate workqueue.TypedRateLimiter[T]
	maxDelay time.Duration
	jitter   func(time.Duration, float64) time.Duration
}

func (r *jitteredRateLimiter[T]) When(item T) time.Duration {
	delay := r.delegate.When(item)
	if delay <= 0 || delay >= r.maxDelay {
		return delay
	}
	return min(r.jitter(delay, backoffJitterMaxFactor), r.maxDelay)
}

func (r *jitteredRateLimiter[T]) Forget(item T) {
	r.delegate.Forget(item)
}

func (r *jitteredRateLimiter[T]) NumRequeues(item T) int {
	return r.delegate.NumRequeues(item)
}

func newControllerOptions(baseDelay, maxDelay time.Duration) ctrlcontroller.Options {
	perItemLimiter := &jitteredRateLimiter[reconcile.Request]{
		delegate: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](baseDelay, maxDelay),
		maxDelay: maxDelay,
		jitter:   wait.Jitter,
	}
	overallLimiter := &workqueue.TypedBucketRateLimiter[reconcile.Request]{
		Limiter: rate.NewLimiter(controllerRateLimit, controllerRateBurst),
	}

	return ctrlcontroller.Options{
		RateLimiter: workqueue.NewTypedMaxOfRateLimiter(perItemLimiter, overallLimiter),
	}
}
