package controller

import (
	"io"
	"net/http"
	"net/http/httptest"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
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

type aggregatorSyncFixture struct {
	statusCode   int
	body         string
	capturedBody []byte
	capturedPath string
	server       *httptest.Server
	recorder     *record.FakeRecorder
}

func newAggregatorSyncFixture() *aggregatorSyncFixture {
	f := &aggregatorSyncFixture{}
	f.reset()
	f.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.capturedBody, _ = io.ReadAll(r.Body)
		f.capturedPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(f.statusCode)
		if f.body != "" {
			_, _ = w.Write([]byte(f.body))
		}
	}))
	f.recorder = record.NewFakeRecorder(16)
	return f
}

func (f *aggregatorSyncFixture) reset() {
	f.statusCode = http.StatusOK
	f.body = ""
	f.capturedBody = nil
	f.capturedPath = ""
}

func (f *aggregatorSyncFixture) close() {
	if f.server != nil {
		f.server.Close()
	}
	if f.recorder != nil {
		drainRecordedEvents(f.recorder.Events)
	}
}

func reconcileAndFetchObject[T client.Object](
	reconciler reconcile.Reconciler,
	key types.NamespacedName,
	newObj func() T,
) (T, reconcile.Result, error) {
	result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: key})
	obj := newObj()
	Expect(k8sClient.Get(ctx, key, obj)).To(Succeed())
	return obj, result, err
}

func deleteIfExists(obj client.Object) {
	err := k8sClient.Get(ctx, client.ObjectKeyFromObject(obj), obj)
	if err == nil {
		Expect(k8sClient.Delete(ctx, obj)).To(Succeed())
	}
}
