package controller

import (
	"sync"
	"testing"
	"time"
)

const testEDBKey = "test-ns/test-edb"

func TestExternalDatabaseBindingTriggerLifecycle(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

func TestInternalDatabaseBindingTriggerLifecycle(t *testing.T) {
	r := &InternalDatabaseReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

// clearAsyncStart deletes the key's async-operation start stamp, and a second
// call for a key that no longer has one is a no-op.
func TestInternalDatabaseClearAsyncStart(t *testing.T) {
	key := "test-ns/test-dd"
	r := &InternalDatabaseReconciler{
		asyncStartTimes: map[string]time.Time{
			key: time.Unix(100, 0),
		},
	}

	r.clearAsyncStart(key)
	r.clearAsyncStart(key)

	if _, ok := r.asyncStartTimes[key]; ok {
		t.Fatalf("asyncStartTimes[%q] exists after clearAsyncStart, want deleted", key)
	}
}

func TestDatabaseAccessPolicyBindingTriggerLifecycle(t *testing.T) {
	r := &DatabaseAccessPolicyReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

// Stamping and consuming one key from separate goroutines is safe. The concurrent
// phase asserts nothing by itself: an unguarded stamp map surfaces as a concurrent
// map write panic, or as a report under go test -race. The explicit check is that
// clearBindingTrigger, once the goroutines have finished, leaves nothing to consume.
func TestExternalDatabaseTriggerStampsConcurrentAccess(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	key := testEDBKey

	var wg sync.WaitGroup
	for range 50 {
		wg.Add(2)
		go func() {
			defer wg.Done()
			r.stampBindingTrigger(key)
		}()
		go func() {
			defer wg.Done()
			_ = r.consumeBindingTrigger(key)
		}()
	}
	wg.Wait()

	r.clearBindingTrigger(key)
	if r.consumeBindingTrigger(key) {
		t.Fatalf("consumeBindingTrigger() after concurrent clear = true, want false")
	}
}

// assertBindingTriggerLifecycle checks the bindingTriggerTracker methods one
// reconciler promotes, passed as method values so each reconciler type is covered
// separately. Stamping is idempotent: after two stamps the first consume reports
// true and the second false. Clearing drops a pending stamp, so the consume that
// follows reports false too.
func assertBindingTriggerLifecycle(t *testing.T, stamp func(string), consume func(string) bool, clear func(string)) {
	t.Helper()

	key := "test-ns/test-resource"

	stamp(key)
	stamp(key)

	if !consume(key) {
		t.Fatalf("consume() = false, want true")
	}
	if consume(key) {
		t.Fatalf("second consume() = true, want false")
	}

	stamp(key)
	clear(key)
	if consume(key) {
		t.Fatalf("consume() after clear = true, want false")
	}
}
