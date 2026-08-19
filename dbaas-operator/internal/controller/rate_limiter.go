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
	"time"

	"k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/client-go/util/workqueue"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const backoffJitterFactor = 0.1

// RateLimiterConfig contains immutable retry settings. Each controller builds
// its own limiter from this config so workqueue state cannot be shared between
// controllers accidentally.
type RateLimiterConfig struct {
	baseDelay time.Duration
	maxDelay  time.Duration
}

// NewRateLimiterConfig creates retry settings for controller workqueues.
func NewRateLimiterConfig(baseDelay, maxDelay time.Duration) RateLimiterConfig {
	return RateLimiterConfig{baseDelay: baseDelay, maxDelay: maxDelay}
}

func (c RateLimiterConfig) controllerOptions() ctrlcontroller.Options {
	return ctrlcontroller.Options{
		RateLimiter: &jitteredRateLimiter{
			delegate: workqueue.NewTypedItemExponentialFailureRateLimiter[reconcile.Request](
				c.baseDelay,
				c.maxDelay,
			),
			maxDelay: c.maxDelay,
		},
	}
}

type jitteredRateLimiter struct {
	delegate workqueue.TypedRateLimiter[reconcile.Request]
	maxDelay time.Duration
}

func (r *jitteredRateLimiter) When(item reconcile.Request) time.Duration {
	delay := r.delegate.When(item)
	if delay <= 0 {
		return delay
	}
	if delay >= r.maxDelay {
		// Jitter downward at the cap so long outages do not make every object
		// converge on the same retry instant.
		return r.maxDelay - (wait.Jitter(r.maxDelay, backoffJitterFactor) - r.maxDelay)
	}
	return min(wait.Jitter(delay, backoffJitterFactor), r.maxDelay)
}

func (r *jitteredRateLimiter) Forget(item reconcile.Request) {
	r.delegate.Forget(item)
}

func (r *jitteredRateLimiter) NumRequeues(item reconcile.Request) int {
	return r.delegate.NumRequeues(item)
}
