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

	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/ownership"
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
	GinkgoHelper()
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

// mineOwnershipResolver returns an OwnershipResolver whose cache is pre-seeded
// with Mine state for each supplied namespace.  Tests use this so that the
// ownership check fast-path always succeeds without hitting the API server.
func mineOwnershipResolver(namespaces ...string) *ownership.OwnershipResolver {
	const testLocation = "test-namespace"
	r := ownership.NewOwnershipResolver(testLocation, k8sClient)
	for _, ns := range namespaces {
		r.SetOwner(ns, testLocation)
	}
	return r
}

// foreignOwnershipResolver returns an OwnershipResolver whose cache is
// pre-seeded with Foreign state for each supplied namespace.
func foreignOwnershipResolver(namespaces ...string) *ownership.OwnershipResolver {
	const testLocation = "test-namespace"
	r := ownership.NewOwnershipResolver(testLocation, k8sClient)
	for _, ns := range namespaces {
		r.SetOwner(ns, "other-operator-ns") // different from testLocation → Foreign
	}
	return r
}

// emptyOwnershipResolver returns an OwnershipResolver with an empty cache and
// no NamespaceBinding objects in the API server.  IsMyNamespace will perform a
// live GET, find nothing, and return (false, nil) — leaving the state Unknown.
func emptyOwnershipResolver() *ownership.OwnershipResolver {
	return ownership.NewOwnershipResolver("test-namespace", k8sClient)
}
