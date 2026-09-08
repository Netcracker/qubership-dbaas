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

package controller

import (
	"strings"
	"testing"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

func TestValidateInternalDatabaseSpec_scope(t *testing.T) {
	t.Parallel()

	base := func() *dbaasv1.InternalDatabase {
		return &dbaasv1.InternalDatabase{
			Spec: dbaasv1.InternalDatabaseSpec{
				Classifier: dbaasv1.Classifier{MicroserviceName: "svc", Scope: dbaasv1.ScopeService},
				Type:       "postgresql",
			},
		}
	}

	t.Run("accepts service and tenant", func(t *testing.T) {
		for _, scope := range []string{dbaasv1.ScopeService, dbaasv1.ScopeTenant} {
			dd := base()
			dd.Spec.Classifier.Scope = scope
			if msg := validateInternalDatabaseSpec(dd); msg != "" {
				t.Errorf("scope %q: got %q, want no error", scope, msg)
			}
		}
	})

	t.Run("rejects an unknown scope", func(t *testing.T) {
		dd := base()
		dd.Spec.Classifier.Scope = "Service"
		msg := validateInternalDatabaseSpec(dd)
		if !strings.Contains(msg, "spec.classifier.scope") || !strings.Contains(msg, `"Service"`) {
			t.Errorf("got %q, want a message naming spec.classifier.scope and the bad value", msg)
		}
	})

	t.Run("rejects an unknown scope on sourceClassifier", func(t *testing.T) {
		dd := base()
		dd.Spec.InitialInstantiation = &dbaasv1.InitialInstantiation{
			Approach:         "clone",
			SourceClassifier: &dbaasv1.Classifier{MicroserviceName: "svc", Scope: "bogus"},
		}
		msg := validateInternalDatabaseSpec(dd)
		if !strings.Contains(msg, "spec.initialInstantiation.sourceClassifier.scope") {
			t.Errorf("got %q, want a message naming sourceClassifier.scope", msg)
		}
	})
}
