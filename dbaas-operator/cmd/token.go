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
	"strings"

	"github.com/fsnotify/fsnotify"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
)

// tokenSetter is the narrow interface required by watchToken.
// *aggregatorclient.AggregatorClient satisfies it automatically.
type tokenSetter interface {
	SetToken(token string)
}

// loadToken reads the Kubernetes service account token from path.
// Calls os.Exit(1) if the file is missing or empty — the token is required
// for the operator to authenticate with dbaas-aggregator.
func loadToken(log logging.Logger, path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		log.Errorf("failed to load service account token from %q: %v", path, err)
		os.Exit(1)
	}
	token := strings.TrimSpace(string(data))
	if token == "" {
		log.Errorf("service account token at %q is empty", path)
		os.Exit(1)
	}
	log.Infof("loaded service account token path=%v", path)
	return token
}

// watchToken watches the directory containing tokenPath for changes and reloads
// the token whenever the file is updated.
//
// Kubernetes rotates projected service account tokens roughly every hour via an
// atomic "..data" symlink swap (same mechanism as Secret volume mounts).
// Watching the parent directory and reacting to Create/Rename events on "..data"
// catches this rotation. Direct Write events on the token filename are also
// handled for local-dev environments.
//
// The function blocks until ctx is cancelled. Errors starting the watcher are
// logged and treated as non-fatal (token auto-reload is simply disabled).
func watchToken(ctx context.Context, log logging.Logger, tokenPath string, client tokenSetter) error {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		log.Errorf("failed to create fsnotify watcher — token auto-reload disabled: %v", err)
		return nil
	}
	defer watcher.Close()

	dir := filepath.Dir(tokenPath)
	base := filepath.Base(tokenPath)

	if err := watcher.Add(dir); err != nil {
		log.Errorf("failed to watch token dir — token auto-reload disabled dir=%v: %v", dir, err)
		return nil
	}
	log.Infof("token watcher started path=%v", tokenPath)

	reload := func(trigger string) {
		data, err := os.ReadFile(tokenPath)
		if err != nil {
			log.Errorf("failed to reload token: %v", err)
			return
		}
		token := strings.TrimSpace(string(data))
		if token == "" {
			log.Errorf("reloaded token is empty, keeping previous token")
			return
		}
		client.SetToken(token)
		log.Infof("service account token reloaded trigger=%v", trigger)
	}

	for {
		select {
		case <-ctx.Done():
			log.Info("token watcher stopped")
			return nil
		case event, ok := <-watcher.Events:
			if !ok {
				log.Warn("fsnotify events channel closed — token auto-reload disabled")
				return nil
			}
			eventBase := filepath.Base(event.Name)
			// "..data" — Kubernetes atomic symlink replacement on token rotation.
			// base (e.g. "token") — direct file write in local dev.
			if eventBase != "..data" && eventBase != base {
				continue
			}
			if event.Has(fsnotify.Create) || event.Has(fsnotify.Write) || event.Has(fsnotify.Rename) {
				log.Infof("token file changed event=%v", event.String())
				reload(event.String())
			}
		case err, ok := <-watcher.Errors:
			if !ok {
				log.Warn("fsnotify errors channel closed — token auto-reload disabled")
				return nil
			}
			log.Errorf("fsnotify watcher error: %v", err)
		}
	}
}
