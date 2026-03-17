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
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/go-logr/logr"
)

// ── test helpers ──────────────────────────────────────────────────────────────

// writeUsersJSON writes users.json into dir in the dbaas-aggregator format:
//
//	{ "<username>": { "password": "<password>" } }
func writeUsersJSON(t *testing.T, dir string, users map[string]string) {
	t.Helper()
	m := make(map[string]map[string]string, len(users))
	for u, p := range users {
		m[u] = map[string]string{"password": p}
	}
	data, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("writeUsersJSON: marshal: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "users.json"), data, 0o600); err != nil {
		t.Fatalf("writeUsersJSON: write: %v", err)
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

// ── readCredentialsFromFile ───────────────────────────────────────────────────

func TestReadCredentialsFromFile(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		setup    func(dir string)
		username string
		wantPass string
		wantOK   bool
	}{
		{
			name:     "success",
			setup:    func(dir string) { writeUsersJSON(t, dir, map[string]string{"cluster-dba": "s3cr3t"}) },
			username: "cluster-dba",
			wantPass: "s3cr3t",
			wantOK:   true,
		},
		{
			name:     "user_not_found",
			setup:    func(dir string) { writeUsersJSON(t, dir, map[string]string{"other": "pass"}) },
			username: "cluster-dba",
			wantOK:   false,
		},
		{
			name:     "file_missing",
			setup:    func(dir string) { /* no file */ },
			username: "cluster-dba",
			wantOK:   false,
		},
		{
			name: "invalid_json",
			setup: func(dir string) {
				_ = os.WriteFile(filepath.Join(dir, "users.json"), []byte("not-json"), 0o600)
			},
			username: "cluster-dba",
			wantOK:   false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			tc.setup(dir)

			pass, ok := readCredentialsFromFile(logr.Discard(), dir, tc.username)

			if ok != tc.wantOK {
				t.Errorf("ok = %v, want %v", ok, tc.wantOK)
			}
			if ok && pass != tc.wantPass {
				t.Errorf("password = %q, want %q", pass, tc.wantPass)
			}
		})
	}
}

// ── watchCredentials ──────────────────────────────────────────────────────────

// TestWatchCredentials_ReloadsOnFileChange verifies that overwriting users.json
// triggers a SetCredentials call with the new password.
func TestWatchCredentials_ReloadsOnFileChange(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeUsersJSON(t, dir, map[string]string{"cluster-dba": "initial"})

	mock := newMockSetter()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() { _ = watchCredentials(ctx, logr.Discard(), dir, "cluster-dba", mock) }()

	time.Sleep(100 * time.Millisecond) // allow watcher to register inotify watches

	writeUsersJSON(t, dir, map[string]string{"cluster-dba": "updated"})

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
// test is skipped there — cross-platform watcher behaviour is covered by
// TestWatchCredentials_ReloadsOnFileChange.
func TestWatchCredentials_ReloadsOnKubernetesSymlinkSwap(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("symlink swap test requires Linux inotify semantics; kqueue (macOS) does not emit Create events for symlinks")
	}
	t.Parallel()

	dir := t.TempDir()

	// Set up the initial Kubernetes-style volume mount layout:
	//   dir/..data          → dir/..v1/
	//   dir/users.json      → ..data/users.json  (relative symlink)
	//   dir/..v1/users.json → real file
	v1 := filepath.Join(dir, "..v1")
	if err := os.Mkdir(v1, 0o755); err != nil {
		t.Fatal(err)
	}
	writeUsersJSON(t, v1, map[string]string{"cluster-dba": "v1-pass"})
	if err := os.Symlink(v1, filepath.Join(dir, "..data")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("..data/users.json", filepath.Join(dir, "users.json")); err != nil {
		t.Fatal(err)
	}

	mock := newMockSetter()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() { _ = watchCredentials(ctx, logr.Discard(), dir, "cluster-dba", mock) }()

	time.Sleep(100 * time.Millisecond) // allow watcher to start

	// Simulate kubelet updating the Secret:
	//   1. create new versioned directory with updated content
	//   2. replace the "..data" symlink
	//
	// In production, kubelet uses an atomic rename ("..data.tmp" → "..data").
	// However, on macOS (kqueue), fsnotify reports the Rename event on the
	// SOURCE path ("..data.tmp"), which our filename filter ignores.
	// Using Remove + Symlink generates an explicit Create event on "..data"
	// that works correctly on both Linux (inotify) and macOS (kqueue).
	v2 := filepath.Join(dir, "..v2")
	if err := os.Mkdir(v2, 0o755); err != nil {
		t.Fatal(err)
	}
	writeUsersJSON(t, v2, map[string]string{"cluster-dba": "v2-pass"})

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
// exits cleanly when the context is cancelled.
func TestWatchCredentials_StopsOnContextCancel(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	writeUsersJSON(t, dir, map[string]string{"cluster-dba": "pass"})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- watchCredentials(ctx, logr.Discard(), dir, "cluster-dba", newMockSetter())
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

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := watchCredentials(ctx, logr.Discard(), "/nonexistent/path/xyz", "cluster-dba", newMockSetter())
	if err != nil {
		t.Errorf("expected nil error for missing dir, got %v", err)
	}
}
