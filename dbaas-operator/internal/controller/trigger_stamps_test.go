package controller

import (
	"sync"
	"testing"
	"time"
)

func TestExternalDatabaseSecretTriggerLifecycle(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	key := "test-ns/test-edb"
	start := time.Unix(100, 0)
	later := start.Add(time.Minute)

	r.stampSecretTrigger(key, start)
	r.stampSecretTrigger(key, later)

	if !r.consumeSecretTrigger(key) {
		t.Fatalf("consumeSecretTrigger() = false, want true")
	}
	if r.consumeSecretTrigger(key) {
		t.Fatalf("second consumeSecretTrigger() = true, want false")
	}

	gotStart, ok := r.consumeSecretPropagation(key)
	if !ok {
		t.Fatalf("consumeSecretPropagation() ok = false, want true")
	}
	if !gotStart.Equal(start) {
		t.Fatalf("consumeSecretPropagation() = %v, want earliest %v", gotStart, start)
	}
	if _, ok := r.consumeSecretPropagation(key); ok {
		t.Fatalf("second consumeSecretPropagation() ok = true, want false")
	}
}

func TestExternalDatabaseClearSecretTriggerClearsTriggerAndPropagation(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	key := "test-ns/test-edb"

	r.stampSecretTrigger(key, time.Unix(100, 0))
	r.clearSecretTrigger(key)

	if r.consumeSecretTrigger(key) {
		t.Fatalf("consumeSecretTrigger() after clear = true, want false")
	}
	if _, ok := r.consumeSecretPropagation(key); ok {
		t.Fatalf("consumeSecretPropagation() after clear ok = true, want false")
	}
}

func TestExternalDatabaseBindingTriggerLifecycle(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

func TestDatabaseDeclarationBindingTriggerLifecycle(t *testing.T) {
	r := &DatabaseDeclarationReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

func TestDatabaseDeclarationClearAsyncStart(t *testing.T) {
	key := "test-ns/test-dd"
	r := &DatabaseDeclarationReconciler{
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

func TestDbPolicyBindingTriggerLifecycle(t *testing.T) {
	r := &DbPolicyReconciler{}
	assertBindingTriggerLifecycle(t, r.stampBindingTrigger, r.consumeBindingTrigger, r.clearBindingTrigger)
}

func TestExternalDatabaseTriggerStampsConcurrentAccess(t *testing.T) {
	r := &ExternalDatabaseReconciler{}
	key := "test-ns/test-edb"

	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(5)
		go func(i int) {
			defer wg.Done()
			r.stampSecretTrigger(key, time.Unix(int64(i), 0))
		}(i)
		go func() {
			defer wg.Done()
			_ = r.consumeSecretTrigger(key)
		}()
		go func() {
			defer wg.Done()
			_, _ = r.consumeSecretPropagation(key)
		}()
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

	r.clearSecretTrigger(key)
	r.clearBindingTrigger(key)

	if r.consumeSecretTrigger(key) {
		t.Fatalf("consumeSecretTrigger() after concurrent clear = true, want false")
	}
	if _, ok := r.consumeSecretPropagation(key); ok {
		t.Fatalf("consumeSecretPropagation() after concurrent clear ok = true, want false")
	}
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
