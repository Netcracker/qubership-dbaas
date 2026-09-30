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
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"github.com/netcracker/qubership-core-lib-go/v3/security/tokensource"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"

	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/requestcontext"
)

type stubTokenSource struct{}

func (s *stubTokenSource) GetAudienceToken(_ context.Context, audience tokensource.TokenAudience) (string, error) {
	return "k8s-token-" + string(audience), nil
}

func (s *stubTokenSource) GetServiceAccountToken(_ context.Context) (string, error) {
	return "", nil
}

func init() {
	serviceloader.Register(100, &stubTokenSource{})
}

func TestNewAggregatorClient_SelectsAuthByMode(t *testing.T) {
	tests := []struct {
		mode        security.M2MAuthMode
		wantAuth    string
		wantWatcher bool
	}{
		{mode: security.M2MAuthModeLegacy, wantAuth: "Basic ZGJhYXMtb3BlcmF0b3I6c2VjcmV0", wantWatcher: true},
		{mode: security.M2MAuthModeHybrid, wantAuth: "Bearer k8s-token-dbaas"},
		{mode: security.M2MAuthModeK8s, wantAuth: "Bearer k8s-token-dbaas"},
	}
	registerContextProviders()
	for _, tt := range tests {
		t.Run(string(tt.mode), func(t *testing.T) {
			var gotAuth string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotAuth = r.Header.Get("Authorization")
				w.WriteHeader(http.StatusNotFound)
			}))
			t.Cleanup(server.Close)
			securityDir := t.TempDir()
			writeUsersJSON(t, securityDir, "secret")

			aggregator, credentialWatcher := newAggregatorClient(logging.GetLogger("dbaas-operator"), tt.mode, server.URL, securityDir)
			ctx, _ := requestcontext.WithFreshRequestID(context.Background())
			_, _ = aggregator.GetOperationStatus(ctx, "tracking-id")

			if gotAuth != tt.wantAuth {
				t.Errorf("Authorization = %q, want %q", gotAuth, tt.wantAuth)
			}
			if (credentialWatcher != nil) != tt.wantWatcher {
				t.Errorf("credential watcher returned = %v, want %v", credentialWatcher != nil, tt.wantWatcher)
			}
		})
	}
}
