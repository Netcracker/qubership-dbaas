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
	"fmt"
	"os"
	"path/filepath"

	"github.com/fsnotify/fsnotify"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
)

// securityDir is the fixed mount path of the dbaas-security-configuration-secret
// (mounted in Basic Auth mode, KUBERNETES_M2M_ENABLED=false).
const securityDir = "/etc/dbaas/security"

const usersJSONFile = "users.json"

// operatorUsername is the fixed Basic Auth username the operator uses to authenticate
// against dbaas-aggregator. It must be present as a key in the mounted users.json.
const operatorUsername = "dbaas-operator"

// credentialsSetter is the narrow interface required by watchCredentials.
// *aggregatorclient.AggregatorClient satisfies it automatically.
type credentialsSetter interface {
	SetCredentials(username, password string)
}

// readCredentials reads the users.json from dir (the mounted
// dbaas-security-configuration-secret) and extracts the username and password for
// the operatorUsername entry. Returns os.ErrNotExist (via errors.Is) when the file
// is absent, so the caller can distinguish "not mounted yet" from a real error.
func readCredentials(dir string) (username, password string, err error) {
	raw, err := os.ReadFile(filepath.Join(dir, usersJSONFile))
	if err != nil {
		return "", "", err
	}
	var users map[string]struct {
		Password string `json:"password"`
	}
	if err := json.Unmarshal(raw, &users); err != nil {
		return "", "", fmt.Errorf("parse %s: %w", usersJSONFile, err)
	}
	entry, ok := users[operatorUsername]
	if !ok {
		return "", "", fmt.Errorf("user %q not found in %s", operatorUsername, usersJSONFile)
	}
	return operatorUsername, entry.Password, nil
}

// loadAggregatorCredentials loads the Basic Auth username and password from the
// mounted dbaas-security-configuration-secret at startup. Calls os.Exit(1) if they
// cannot be read — in Basic Auth mode the Secret is required, and Kubernetes will not
// start the pod until the Secret volume is available, so the file is present in production.
func loadAggregatorCredentials(log logging.Logger, dir string) (username, password string) {
	username, password, err := readCredentials(dir)
	if err != nil {
		log.Errorf("failed to load aggregator credentials from %s — mount the "+
			"dbaas-security-configuration-secret (Basic Auth mode): %v", dir, err)
		os.Exit(1)
	}
	log.Infof("loaded aggregator credentials username=%v", username)
	return username, password
}

// watchCredentials watches dir and reloads the Basic Auth credentials whenever the
// mounted Secret is updated, so a credential rotation is picked up without a pod
// restart.
//
// Kubernetes updates Secret-backed volume mounts atomically via a "..data" symlink
// swap, so we watch the directory (not the file directly) and react to Create/Rename
// events on "..data". Direct Write events on users.json are also handled for
// local-dev mounts.
//
// The function blocks until ctx is cancelled. Errors starting the watcher are logged
// and treated as non-fatal (credential auto-reload is simply disabled).
func watchCredentials(ctx context.Context, log logging.Logger, dir string, client credentialsSetter) error {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		log.Errorf("failed to create fsnotify watcher — credential auto-reload disabled: %v", err)
		return nil
	}
	defer func() { _ = watcher.Close() }()

	if err := watcher.Add(dir); err != nil {
		log.Errorf("failed to watch security dir — credential auto-reload disabled dir=%v: %v", dir, err)
		return nil
	}
	log.Infof("credential watcher started dir=%v", dir)

	reload := func(trigger string) {
		username, password, err := readCredentials(dir)
		if err != nil {
			log.Errorf("failed to reload aggregator credentials: %v", err)
			return
		}
		client.SetCredentials(username, password)
		log.Infof("aggregator credentials reloaded trigger=%v username=%v", trigger, username)
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
			// users.json — direct file write in local dev.
			if base != "..data" && base != usersJSONFile {
				continue
			}
			if event.Has(fsnotify.Create) || event.Has(fsnotify.Write) || event.Has(fsnotify.Rename) {
				log.Infof("credential file changed event=%v", event.String())
				reload(event.String())
			}
		case err, ok := <-watcher.Errors:
			if !ok {
				return nil
			}
			log.Errorf("fsnotify watcher error: %v", err)
		}
	}
}
