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

package main

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
)

// writeToken writes the given token string to a file named "token" inside dir.
func writeToken(t *testing.T, dir, token string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, "token"), []byte(token), 0o600); err != nil {
		t.Fatalf("writeToken: %v", err)
	}
}

// mockTokenSetter records SetToken calls via a buffered channel.
type mockTokenSetter struct{ ch chan string }

func newMockTokenSetter() *mockTokenSetter { return &mockTokenSetter{ch: make(chan string, 16)} }

func (m *mockTokenSetter) SetToken(token string) { m.ch <- token }

// ── watchToken ────────────────────────────────────────────────────────────────

// TestWatchToken_ReloadsOnFileChange verifies that overwriting the token file
// triggers a SetToken call with the new token value.
func TestWatchToken_ReloadsOnFileChange(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeToken(t, dir, "initial-token")
	tokenPath := filepath.Join(dir, "token")

	mock := newMockTokenSetter()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() { _ = watchToken(ctx, logging.GetLogger("test"), tokenPath, mock) }()

	time.Sleep(100 * time.Millisecond) // allow watcher to register

	writeToken(t, dir, "updated-token")

	deadline := time.After(5 * time.Second)
	for {
		select {
		case tok := <-mock.ch:
			if tok == "updated-token" {
				return // ✓
			}
		case <-deadline:
			t.Fatal("timed out waiting for token reload after file change")
		}
	}
}

// TestWatchToken_ReloadsOnKubernetesSymlinkSwap simulates the atomic "..data"
// symlink replacement that kubelet performs when a projected token is rotated.
// Linux-only: same inotify semantics as the credentials watcher test.
func TestWatchToken_ReloadsOnKubernetesSymlinkSwap(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("symlink swap test requires Linux inotify semantics")
	}
	t.Parallel()

	dir := t.TempDir()

	// Set up initial Kubernetes-style projected token layout:
	//   dir/..data     → dir/..v1/
	//   dir/token      → ..data/token  (relative symlink)
	//   dir/..v1/token → real file
	v1 := filepath.Join(dir, "..v1")
	if err := os.Mkdir(v1, 0o755); err != nil {
		t.Fatal(err)
	}
	writeToken(t, v1, "v1-token")
	if err := os.Symlink(v1, filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("..data/token", filepath.Join(dir, "token")); err != nil {
		t.Fatal(err)
	}

	mock := newMockTokenSetter()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	tokenPath := filepath.Join(dir, "token")
	go func() { _ = watchToken(ctx, logging.GetLogger("test"), tokenPath, mock) }()

	time.Sleep(100 * time.Millisecond)

	// Simulate kubelet token rotation: create new versioned dir, swap symlink.
	v2 := filepath.Join(dir, "..v2")
	if err := os.Mkdir(v2, 0o755); err != nil {
		t.Fatal(err)
	}
	writeToken(t, v2, "v2-token")

	if err := os.Remove(filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(v2, filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}

	deadline := time.After(5 * time.Second)
	for {
		select {
		case tok := <-mock.ch:
			if tok == "v2-token" {
				return // ✓
			}
		case <-deadline:
			t.Fatal("timed out waiting for token reload after symlink swap")
		}
	}
}

// TestWatchToken_StopsOnContextCancel verifies that the watcher goroutine
// exits cleanly when the context is cancelled.
func TestWatchToken_StopsOnContextCancel(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeToken(t, dir, "some-token")

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- watchToken(ctx, logging.GetLogger("test"), filepath.Join(dir, "token"), newMockTokenSetter())
	}()

	time.Sleep(50 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Errorf("expected nil, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("watchToken did not exit after context cancel")
	}
}

// TestWatchToken_GracefulDegradationWhenDirMissing verifies that a missing
// token directory is handled gracefully (returns nil, no panic).
func TestWatchToken_GracefulDegradationWhenDirMissing(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := watchToken(ctx, logging.GetLogger("test"), "/nonexistent/path/token", newMockTokenSetter())
	if err != nil {
		t.Errorf("expected nil error for missing dir, got %v", err)
	}
}
