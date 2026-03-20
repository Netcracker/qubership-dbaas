package controller

import (
	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func findCondition(conditions []metav1.Condition, condType string) *metav1.Condition {
	for i := range conditions {
		if conditions[i].Type == condType {
			return &conditions[i]
		}
	}
	return nil
}

func expectRecordedEvent(events <-chan string, eventtype, reason string) {
	GinkgoHelper()
	Expect(events).To(Receive(HavePrefix(eventtype + " " + reason)))
}

func expectNoRecordedEvent(events <-chan string) {
	GinkgoHelper()
	Expect(events).NotTo(Receive())
}

func expectRecordedEventContaining(events <-chan string, eventtype, reason, substr string) {
	GinkgoHelper()
	Expect(events).To(Receive(And(
		HavePrefix(eventtype+" "+reason),
		ContainSubstring(substr),
	)))
}

func drainRecordedEvents(events <-chan string) {
	for {
		select {
		case <-events:
		default:
			return
		}
	}
}
