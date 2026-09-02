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
	"sync"
	"time"

	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/wait"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

// Bounded exponential backoff for "not ready yet" polling — an async operation
// still in progress, a database not yet provisioned, an empty connectionProperties
// response. This is deliberately separate from the workqueue rate limiter in
// rate_limiter.go, which only governs requeues triggered by a non-nil reconcile
// error; these paths return a nil error and would otherwise poll at a flat
// interval forever.
const (
	pollInitialInterval = 5 * time.Second
	pollMaxInterval     = 1 * time.Minute
	pollJitterFactor    = 0.1
)

// pollDelayForStep computes the unjittered delay for step by repeated doubling
// from pollInitialInterval, capped at pollMaxInterval. Doubling stops as soon as
// the delay reaches the cap, so an arbitrarily large step cannot overflow delay.
func pollDelayForStep(step int32) time.Duration {
	delay := pollInitialInterval
	for i := int32(0); i < step && delay < pollMaxInterval; i++ {
		delay *= 2
	}
	if delay > pollMaxInterval {
		delay = pollMaxInterval
	}
	return delay
}

// jitteredPollDelay adds up to pollJitterFactor of jitter to delay so that CRs
// created in the same rollout wave do not stay synchronized on the same poll
// instant. Below the cap it jitters upward (result in [delay, delay*(1+factor)));
// at the cap it jitters downward instead, the same idiom jitteredRateLimiter.When
// uses in rate_limiter.go, so the result never exceeds pollMaxInterval.
func jitteredPollDelay(delay time.Duration) time.Duration {
	if delay >= pollMaxInterval {
		jitter := wait.Jitter(pollMaxInterval, pollJitterFactor) - pollMaxInterval
		return pollMaxInterval - jitter
	}
	return min(wait.Jitter(delay, pollJitterFactor), pollMaxInterval)
}

type pollBackoffState struct {
	uid        types.UID
	generation int64
	step       int32
}

// pollBackoffTracker keeps healthy polling cadence separate from the public CR
// status and from the workqueue rate limiter used for reconcile errors. Its zero
// value is ready to use. State is local to one reconciler and intentionally
// resets when the operator process or leader changes.
type pollBackoffTracker struct {
	mu     sync.Mutex
	states map[types.NamespacedName]pollBackoffState
}

// schedule advances one object's bounded exponential polling sequence. A new
// UID or generation starts at the initial interval.
func (t *pollBackoffTracker) schedule(obj client.Object) ctrl.Result {
	key := client.ObjectKeyFromObject(obj)

	t.mu.Lock()
	defer t.mu.Unlock()

	if t.states == nil {
		t.states = make(map[types.NamespacedName]pollBackoffState)
	}
	state, exists := t.states[key]
	if !exists || state.uid != obj.GetUID() || state.generation != obj.GetGeneration() {
		state = pollBackoffState{uid: obj.GetUID(), generation: obj.GetGeneration()}
	}

	delay := pollDelayForStep(state.step)
	if delay < pollMaxInterval {
		state.step++
	}
	t.states[key] = state
	return ctrl.Result{RequeueAfter: jitteredPollDelay(delay)}
}

// forget clears an object's polling cycle. The next schedule starts at the
// initial interval.
func (t *pollBackoffTracker) forget(key types.NamespacedName) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.states, key)
}

func (t *pollBackoffTracker) forgetObject(obj client.Object) {
	t.forget(client.ObjectKeyFromObject(obj))
}
