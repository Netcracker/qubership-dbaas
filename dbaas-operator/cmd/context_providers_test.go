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
	"testing"

	"github.com/netcracker/qubership-dbaas/dbaas-operator/internal/requestcontext"
)

func TestRegisterContextProviders(t *testing.T) {
	registerContextProviders()

	ctx, want := requestcontext.WithFreshRequestID(context.Background())
	got, err := requestcontext.RequestID(ctx)
	if err != nil {
		t.Fatalf("registered provider did not initialize request context: %v", err)
	}
	if got != want {
		t.Fatalf("request ID = %q, want %q", got, want)
	}
}
