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
	"fmt"
	"time"

	"k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/client-go/util/workqueue"
	ctrlcontroller "sigs.k8s.io/controller-runtime/pkg/controller"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const backoffJitterFactor = 0.1

// Fallback retry settings. The backoff startup flags default to these values,
// so the flag defaults and the fallback cannot drift apart.
const (
	DefaultBackoffBaseDelay = 1 * time.Second
	DefaultBackoffMaxDelay  = 5 * time.Minute
)

// RateLimiterConfig contains immutable retry settings. Each controller builds
// its own limiter from this config so workqueue state cannot be shared between
// controllers accidentally.
type RateLimiterConfig struct {
	baseDelay time.Duration
	maxDelay  time.Duration
}

// NewRateLimiterConfig creates retry settings for controller workqueues.
func NewRateLimiterConfig(baseDelay, maxDelay time.Duration) RateLimiterConfig {
	return RateLimiterConfig{baseDelay: baseDelay, maxDelay: maxDelay}.normalized()
}

// normalized replaces out-of-range delays with the defaults: a non-positive base
// delay reaches the zero-delay shortcut in When and requeues in a hot loop, and a
// maximum below the base caps every retry before its first doubling. Both readers
// below apply it, so the zero value is as safe as a constructed one.
func (c RateLimiterConfig) normalized() RateLimiterConfig {
	if c.baseDelay <= 0 {
		c.baseDelay = DefaultBackoffBaseDelay
	}
	if c.maxDelay < c.baseDelay {
		c.maxDelay = max(DefaultBackoffMaxDelay, c.baseDelay)
	}
	return c
}

// String reports the effective settings, including any that normalized replaced,
// so the startup log cannot drift from the delays the controllers really use.
func (c RateLimiterConfig) String() string {
	c = c.normalized()
	return fmt.Sprintf("base=%v max=%v jitter=%d%%",
		c.baseDelay, c.maxDelay, int(backoffJitterFactor*100))
}

func (c RateLimiterConfig) controllerOptions() ctrlcontroller.Options {
	c = c.normalized()
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
		jitter := wait.Jitter(r.maxDelay, backoffJitterFactor) - r.maxDelay
		return r.maxDelay - jitter
	}
	return min(wait.Jitter(delay, backoffJitterFactor), r.maxDelay)
}

func (r *jitteredRateLimiter) Forget(item reconcile.Request) {
	r.delegate.Forget(item)
}

func (r *jitteredRateLimiter) NumRequeues(item reconcile.Request) int {
	return r.delegate.NumRequeues(item)
}
