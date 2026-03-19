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
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/fsnotify/fsnotify"
	"github.com/go-logr/logr"
)

// credentialsSetter is the narrow interface required by watchCredentials.
// *aggregatorclient.AggregatorClient satisfies it automatically.
type credentialsSetter interface {
	SetCredentials(username, password string)
}

// parseUsersFile reads {securityDir}/users.json and returns the password for
// the given username.
//
// Returns os.ErrNotExist (via errors.Is) when the file is absent — the caller
// can use this to distinguish "file not mounted yet" from a real config error.
// Any other error (malformed JSON, missing user) is returned as-is.
func parseUsersFile(securityDir, username string) (string, error) {
	usersFile := filepath.Join(securityDir, "users.json")
	data, err := os.ReadFile(usersFile)
	if err != nil {
		return "", err
	}
	var users map[string]struct {
		Password string `json:"password"`
	}
	if err := json.Unmarshal(data, &users); err != nil {
		return "", fmt.Errorf("parse %s: %w", usersFile, err)
	}
	cfg, ok := users[username]
	if !ok {
		return "", fmt.Errorf("user %q not found in %s", username, usersFile)
	}
	return cfg.Password, nil
}

// loadAggregatorCredentials loads the password for username at startup.
// Calls os.Exit(1) if credentials cannot be resolved.
//
// Resolution order:
//  1. users.json inside securityDir — the same Secret that dbaas-aggregator
//     itself uses (dbaas-security-configuration-secret). Kubernetes will not
//     start the pod until the Secret volume is available, so the file is
//     guaranteed to exist in production.
//  2. DBAAS_AGGREGATOR_PASSWORD env var — local development fallback when
//     running outside Kubernetes without a mounted Secret.
func loadAggregatorCredentials(log logr.Logger, securityDir, username string) string {
	password, err := parseUsersFile(securityDir, username)
	if err == nil {
		log.Info("loaded aggregator credentials from users.json", "username", username)
		return password
	}

	if !errors.Is(err, os.ErrNotExist) {
		// File exists but is malformed or the user entry is missing — fatal.
		log.Error(err, "failed to load aggregator credentials from users.json")
		os.Exit(1)
	}

	// File absent — fall back to env var (local development without a Secret mount).
	password = os.Getenv("DBAAS_AGGREGATOR_PASSWORD")
	if password != "" {
		log.Info("loaded aggregator credentials from env var", "username", username)
		return password
	}

	log.Error(nil,
		"aggregator credentials not found: mount dbaas-security-configuration-secret at "+
			"DBAAS_SECURITY_CONFIGURATION_LOCATION or set DBAAS_AGGREGATOR_PASSWORD env var")
	os.Exit(1)
	return ""
}

// readCredentialsFromFile parses users.json and returns the password for username.
// Returns ("", false) on any error — the existing credentials remain in use.
// Unlike loadAggregatorCredentials this function never calls os.Exit.
func readCredentialsFromFile(log logr.Logger, securityDir, username string) (string, bool) {
	password, err := parseUsersFile(securityDir, username)
	if err != nil {
		log.Error(err, "failed to read credentials from users.json")
		return "", false
	}
	return password, true
}

// watchCredentials watches securityDir for changes and reloads the aggregator
// credentials whenever users.json is updated.
//
// Kubernetes updates Secret-backed volume mounts atomically via a "..data" symlink
// swap, so we watch the directory (not the file directly) and react to Create/Rename
// events on "..data".  Direct Write events on "users.json" are also handled for
// local-dev / ConfigMap mounts.
//
// The function blocks until ctx is cancelled.  Errors starting the watcher are
// logged and treated as non-fatal (credential auto-reload is simply disabled).
func watchCredentials(ctx context.Context, log logr.Logger, securityDir, username string, client credentialsSetter) error {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		log.Error(err, "failed to create fsnotify watcher — credential auto-reload disabled")
		return nil
	}
	defer watcher.Close()

	if err := watcher.Add(securityDir); err != nil {
		log.Error(err, "failed to watch security dir — credential auto-reload disabled",
			"dir", securityDir)
		return nil
	}
	log.Info("credential watcher started", "dir", securityDir)

	reload := func(trigger string) {
		password, ok := readCredentialsFromFile(log, securityDir, username)
		if ok {
			client.SetCredentials(username, password)
			log.Info("aggregator credentials reloaded", "trigger", trigger, "username", username)
		}
	}

	for {
		select {
		case <-ctx.Done():
			log.Info("credential watcher stopped")
			return nil
		case event, ok := <-watcher.Events:
			if !ok {
				return nil
			}
			base := filepath.Base(event.Name)
			// "..data" — Kubernetes atomic symlink replacement on Secret update.
			// "users.json" — direct file write in local dev / ConfigMap mounts.
			if base != "..data" && base != "users.json" {
				continue
			}
			if event.Has(fsnotify.Create) || event.Has(fsnotify.Write) || event.Has(fsnotify.Rename) {
				log.Info("credential file changed", "event", event.String())
				reload(event.String())
			}
		case err, ok := <-watcher.Errors:
			if !ok {
				return nil
			}
			log.Error(err, "fsnotify watcher error")
		}
	}
}
