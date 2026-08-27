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
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
)

// ── test helpers ──────────────────────────────────────────────────────────────

// writeUsersJSON writes a users.json into dir with a single "dbaas-operator"
// entry, mirroring the dbaas-security-configuration-secret mount layout.
func writeUsersJSON(t *testing.T, dir, password string) {
	t.Helper()
	content := fmt.Sprintf(`{"dbaas-operator":{"password":%q}}`, password)
	if err := os.WriteFile(filepath.Join(dir, usersJSONFile), []byte(content), 0o600); err != nil {
		t.Fatalf("writeUsersJSON: %v", err)
	}
}

// credPair captures a single SetCredentials call.
type credPair struct{ username, password string }

// mockSetter records SetCredentials calls via a buffered channel.
type mockSetter struct{ ch chan credPair }

func newMockSetter() *mockSetter { return &mockSetter{ch: make(chan credPair, 16)} }

func (m *mockSetter) SetCredentials(username, password string) {
	m.ch <- credPair{username, password}
}

// ── readCredentials ───────────────────────────────────────────────────────────

func TestReadCredentials(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		setup    func(dir string)
		wantUser string
		wantPass string
		wantErr  bool
	}{
		{
			name:     "success",
			setup:    func(dir string) { writeUsersJSON(t, dir, "s3cr3t") },
			wantUser: operatorUsername,
			wantPass: "s3cr3t",
		},
		{
			name: "operator_user_missing_from_json",
			setup: func(dir string) {
				content := `{"other-user":{"password":"x"}}`
				_ = os.WriteFile(filepath.Join(dir, usersJSONFile), []byte(content), 0o600)
			},
			wantErr: true,
		},
		{
			name:    "file_missing",
			setup:   func(dir string) { /* empty dir */ },
			wantErr: true,
		},
		{
			name: "invalid_json",
			setup: func(dir string) {
				_ = os.WriteFile(filepath.Join(dir, usersJSONFile), []byte("not-json"), 0o600)
			},
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			tc.setup(dir)

			user, pass, err := readCredentials(dir)

			if (err != nil) != tc.wantErr {
				t.Errorf("err = %v, wantErr = %v", err, tc.wantErr)
			}
			if err == nil {
				if user != tc.wantUser {
					t.Errorf("username = %q, want %q", user, tc.wantUser)
				}
				if pass != tc.wantPass {
					t.Errorf("password = %q, want %q", pass, tc.wantPass)
				}
			}
		})
	}
}

// ── loadAggregatorCredentials ─────────────────────────────────────────────────

// Only the success path is unit-tested; the failure path calls os.Exit(1).
func TestLoadAggregatorCredentials(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeUsersJSON(t, dir, "secret")

	user, pass := loadAggregatorCredentials(logging.GetLogger("dbaas-operator"), dir)

	if user != operatorUsername {
		t.Errorf("username = %q, want %q", user, operatorUsername)
	}
	if pass != "secret" {
		t.Errorf("password = %q, want %q", pass, "secret")
	}
}

// ── watchCredentials ──────────────────────────────────────────────────────────

// TestWatchCredentials_ReloadsOnFileChange verifies that overwriting users.json
// triggers a SetCredentials call with the new password.
func TestWatchCredentials_ReloadsOnFileChange(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeUsersJSON(t, dir, "initial")

	mock := newMockSetter()
	ctx := t.Context()

	go func() { _ = watchCredentials(ctx, logging.GetLogger("dbaas-operator"), dir, mock) }()

	time.Sleep(100 * time.Millisecond) // allow watcher to register inotify watches

	writeUsersJSON(t, dir, "updated")

	// Drain until we see the expected password (stale events are ignored).
	deadline := time.After(5 * time.Second)
	for {
		select {
		case cr := <-mock.ch:
			if cr.password == "updated" {
				return // ✓
			}
		case <-deadline:
			t.Fatal("timed out waiting for credential reload after file change")
		}
	}
}

// TestWatchCredentials_ReloadsOnKubernetesSymlinkSwap simulates the atomic
// "..data" symlink replacement that kubelet performs when a Secret is updated.
//
// Linux-only: inotify (production platform) generates IN_MOVED_TO → Create
// for the destination of a rename, which our filter catches as base="..data".
// macOS kqueue does not generate Create events for symlink creation, so this
// test is skipped there — cross-platform watcher behavior is covered by
// TestWatchCredentials_ReloadsOnFileChange.
func TestWatchCredentials_ReloadsOnKubernetesSymlinkSwap(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("symlink swap test requires Linux inotify semantics; kqueue (macOS) does not emit Create events for symlinks")
	}
	t.Parallel()

	dir := t.TempDir()

	// Set up the initial Kubernetes-style volume mount layout:
	//   dir/..data       → dir/..v1/
	//   dir/users.json   → ..data/users.json  (relative symlink)
	//   dir/..v1/users.json → real file
	v1 := filepath.Join(dir, "..v1")
	if err := os.Mkdir(v1, 0o755); err != nil {
		t.Fatal(err)
	}
	writeUsersJSON(t, v1, "v1-pass")
	if err := os.Symlink(v1, filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("..data/users.json", filepath.Join(dir, usersJSONFile)); err != nil {
		t.Fatal(err)
	}

	mock := newMockSetter()
	ctx := t.Context()

	go func() { _ = watchCredentials(ctx, logging.GetLogger("dbaas-operator"), dir, mock) }()

	time.Sleep(100 * time.Millisecond) // allow watcher to start

	// Simulate kubelet updating the Secret: new versioned dir + replace "..data".
	v2 := filepath.Join(dir, "..v2")
	if err := os.Mkdir(v2, 0o755); err != nil {
		t.Fatal(err)
	}
	writeUsersJSON(t, v2, "v2-pass")

	if err := os.Remove(filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(v2, filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}

	deadline := time.After(5 * time.Second)
	for {
		select {
		case cr := <-mock.ch:
			if cr.password == "v2-pass" {
				return // ✓
			}
		case <-deadline:
			t.Fatal("timed out waiting for credential reload after symlink swap")
		}
	}
}

// TestWatchCredentials_StopsOnContextCancel verifies that the watcher goroutine
// exits cleanly when the context is canceled.
func TestWatchCredentials_StopsOnContextCancel(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeUsersJSON(t, dir, "pass")

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- watchCredentials(ctx, logging.GetLogger("dbaas-operator"), dir, newMockSetter())
	}()

	time.Sleep(50 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Errorf("expected nil, got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("watchCredentials did not exit after context cancel")
	}
}

// TestWatchCredentials_GracefulDegradationWhenDirMissing verifies that a
// missing security directory is handled gracefully (returns nil, no panic).
func TestWatchCredentials_GracefulDegradationWhenDirMissing(t *testing.T) {
	t.Parallel()

	ctx := t.Context()

	err := watchCredentials(ctx, logging.GetLogger("dbaas-operator"), "/nonexistent/path/xyz", newMockSetter())
	if err != nil {
		t.Errorf("expected nil error for missing dir, got %v", err)
	}
}
