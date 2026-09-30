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

package v1

import "testing"

func TestIsAssignedTo(t *testing.T) {
	tests := []struct {
		name                string
		crOperatorNamespace string
		operatorNamespace   string
		want                bool
	}{
		{name: "exact match is assigned", crOperatorNamespace: "dbaas-system", operatorNamespace: "dbaas-system", want: true},
		{name: "different namespace is not assigned", crOperatorNamespace: "other-operator", operatorNamespace: "dbaas-system", want: false},
		{name: "empty CR value never matches a real operator", crOperatorNamespace: "", operatorNamespace: "dbaas-system", want: false},
		{name: "comparison is case sensitive", crOperatorNamespace: "DBaaS-System", operatorNamespace: "dbaas-system", want: false},
		{name: "whitespace is not trimmed", crOperatorNamespace: "dbaas-system ", operatorNamespace: "dbaas-system", want: false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := IsAssignedTo(tt.crOperatorNamespace, tt.operatorNamespace); got != tt.want {
				t.Fatalf("IsAssignedTo(%q, %q) = %v, want %v",
					tt.crOperatorNamespace, tt.operatorNamespace, got, tt.want)
			}
		})
	}
}
