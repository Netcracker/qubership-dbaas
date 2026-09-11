package controller

import (
	"io"
	"net/http"
	"net/http/httptest"
	"time"

	. "github.com/onsi/ginkgo/v2"
	. "github.com/onsi/gomega"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const (
	testOperatorNamespace        = "test-namespace"
	testForeignOperatorNamespace = "other-operator-namespace"
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
	closeServerAndDrain(f.server, f.recorder)
}

// closeServerAndDrain shuts down a test aggregator server and drains any buffered
// recorder events. Shared by the per-controller test fixtures' close().
func closeServerAndDrain(srv *httptest.Server, rec *record.FakeRecorder) {
	if srv != nil {
		srv.Close()
	}
	if rec != nil {
		drainRecordedEvents(rec.Events)
	}
}

func reconcileAndFetchObject[T client.Object](
	reconciler reconcile.Reconciler,
	key types.NamespacedName,
	newObj func() T,
) (T, reconcile.Result, error) {
	GinkgoHelper()
	// Wait for the caching client to reflect the object before calling Reconcile,
	// so that reconcilers using cacheClient (required for MatchingFields) can find it.
	Eventually(func() error {
		return cacheClient.Get(ctx, key, newObj())
	}).Should(Succeed())
	result, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: key})
	obj := newObj()
	Expect(k8sClient.Get(ctx, key, obj)).To(Succeed())
	return obj, result, err
}

// expectRequeueAfterStep asserts that result.RequeueAfter is the jittered delay
// for the given backoff step: in [nominal, nominal*(1+pollJitterFactor)) below
// the cap, or in (pollMaxInterval*(1-pollJitterFactor), pollMaxInterval] once
// pollDelayForStep(step) has reached the cap.
func expectRequeueAfterStep(result ctrl.Result, step int32) {
	GinkgoHelper()
	nominal := pollDelayForStep(step)
	if nominal >= pollMaxInterval {
		Expect(result.RequeueAfter).To(BeNumerically(">", time.Duration(float64(pollMaxInterval)*(1-pollJitterFactor))))
		Expect(result.RequeueAfter).To(BeNumerically("<=", pollMaxInterval))
		return
	}
	Expect(result.RequeueAfter).To(BeNumerically(">=", nominal))
	Expect(result.RequeueAfter).To(BeNumerically("<", time.Duration(float64(nominal)*(1+pollJitterFactor))))
}

func deleteIfExists(obj client.Object) {
	err := k8sClient.Get(ctx, client.ObjectKeyFromObject(obj), obj)
	if err == nil {
		if len(obj.GetFinalizers()) > 0 {
			obj.SetFinalizers(nil)
			Expect(k8sClient.Update(ctx, obj)).To(Succeed())
		}
		Expect(client.IgnoreNotFound(k8sClient.Delete(ctx, obj))).To(Succeed())
	}
}
