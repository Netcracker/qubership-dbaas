package controller

import (
	"sync"
	"testing"
	"time"
)

// testEDBKey is the namespace/name key reused across trigger-stamp tests.
const testEDBKey = "test-ns/test-edb"

func TestExternalDatabaseBindingTriggerLifecycle(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

func TestInternalDatabaseBindingTriggerLifecycle(t *testing.T) {
	r := &InternalDatabaseReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

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
