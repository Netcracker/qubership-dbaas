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

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

var _ = Describe("pollDelayForStep", func() {
	It("progresses through 5s, 10s, 20s, 40s, 60s", func() {
		Expect(pollDelayForStep(0)).To(Equal(5 * time.Second))
		Expect(pollDelayForStep(1)).To(Equal(10 * time.Second))
		Expect(pollDelayForStep(2)).To(Equal(20 * time.Second))
		Expect(pollDelayForStep(3)).To(Equal(40 * time.Second))
		Expect(pollDelayForStep(4)).To(Equal(60 * time.Second))
	})

	It("stays capped at pollMaxInterval for any step beyond the cap", func() {
		Expect(pollDelayForStep(5)).To(Equal(pollMaxInterval))
		Expect(pollDelayForStep(6)).To(Equal(pollMaxInterval))
		Expect(pollDelayForStep(1000)).To(Equal(pollMaxInterval))
		Expect(pollDelayForStep(1 << 30)).To(Equal(pollMaxInterval))
	})
})

var _ = Describe("jitteredPollDelay", func() {
	It("jitters upward and stays below the cap for a sub-cap delay", func() {
		for i := 0; i < 50; i++ {
			d := jitteredPollDelay(10 * time.Second)
			Expect(d).To(BeNumerically(">=", 10*time.Second))
			Expect(d).To(BeNumerically("<", 11*time.Second))
		}
	})

	It("never exceeds pollMaxInterval and stays within the documented jitter band at the cap", func() {
		for i := 0; i < 50; i++ {
			d := jitteredPollDelay(pollMaxInterval)
			Expect(d).To(BeNumerically("<=", pollMaxInterval))
			Expect(d).To(BeNumerically(">", time.Duration(float64(pollMaxInterval)*(1-pollJitterFactor))))
		}
	})

	It("clamps a delay already above the cap down to at most pollMaxInterval", func() {
		d := jitteredPollDelay(2 * pollMaxInterval)
		Expect(d).To(BeNumerically("<=", pollMaxInterval))
	})
})

var _ = Describe("pollBackoffTracker", func() {
	newObject := func() *corev1.ConfigMap {
		return &corev1.ConfigMap{ObjectMeta: metav1.ObjectMeta{
			Namespace: "test", Name: "database", UID: "uid-1", Generation: 1,
		}}
	}

	It("advances through the bounded sequence for one object", func() {
		tracker := pollBackoffTracker{}
		obj := newObject()

		for step := int32(0); step <= 5; step++ {
			expectRequeueAfterStep(tracker.schedule(obj), step)
		}

		state := tracker.states[types.NamespacedName{Namespace: obj.Namespace, Name: obj.Name}]
		Expect(state.step).To(Equal(int32(4)), "the stored step must stop growing at the cap")
	})

	It("starts fresh after the object is forgotten", func() {
		tracker := pollBackoffTracker{}
		obj := newObject()
		expectRequeueAfterStep(tracker.schedule(obj), 0)
		expectRequeueAfterStep(tracker.schedule(obj), 1)

		tracker.forgetObject(obj)

		expectRequeueAfterStep(tracker.schedule(obj), 0)
	})

	It("starts fresh for a new generation or replacement UID", func() {
		tracker := pollBackoffTracker{}
		obj := newObject()
		expectRequeueAfterStep(tracker.schedule(obj), 0)
		expectRequeueAfterStep(tracker.schedule(obj), 1)

		obj.Generation++
		expectRequeueAfterStep(tracker.schedule(obj), 0)

		obj.UID = "uid-2"
		expectRequeueAfterStep(tracker.schedule(obj), 0)
	})

	It("starts fresh after a reconciler restart", func() {
		obj := newObject()
		firstTracker := pollBackoffTracker{}
		expectRequeueAfterStep(firstTracker.schedule(obj), 0)
		expectRequeueAfterStep(firstTracker.schedule(obj), 1)

		restartedTracker := pollBackoffTracker{}
		expectRequeueAfterStep(restartedTracker.schedule(obj), 0)
	})
})
