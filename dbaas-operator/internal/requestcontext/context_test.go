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

package requestcontext

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
)

func TestMain(m *testing.M) {
	RegisterProviders()
	os.Exit(m.Run())
}

func TestWithFreshRequestID(t *testing.T) {
	t.Parallel()

	ctx, generated := WithFreshRequestID(context.Background())
	if _, err := uuid.Parse(generated); err != nil {
		t.Fatalf("generated request ID %q is not a UUID: %v", generated, err)
	}

	got, err := RequestID(ctx)
	if err != nil {
		t.Fatalf("RequestID() error = %v", err)
	}
	if got != generated {
		t.Fatalf("RequestID() = %q, want %q", got, generated)
	}
}

func TestRequestIDExplainsUninitializedContext(t *testing.T) {
	t.Parallel()

	_, err := RequestID(context.Background())
	if err == nil {
		t.Fatal("expected an uninitialized context error")
	}
	if !strings.Contains(err.Error(), "call WithFreshRequestID") {
		t.Fatalf("error = %q, want actionable WithFreshRequestID guidance", err)
	}
}
