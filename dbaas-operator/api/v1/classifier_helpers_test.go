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

import (
	"encoding/json"
	"reflect"
	"testing"

	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
)

func jsonVal(raw string) apiextensionsv1.JSON {
	return apiextensionsv1.JSON{Raw: []byte(raw)}
}

func TestClassifierFlatMap_RequiredFieldsOnly(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
	})
	want := map[string]any{
		"microserviceName": "svc",
		"scope":            "service",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ClassifierFlatMap() = %#v, want %#v", got, want)
	}
}

func TestClassifierFlatMap_OptionalScalarsWhenSet(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "tenant",
		Namespace:        "ns",
		TenantId:         "t-1",
	})
	want := map[string]any{
		"microserviceName": "svc",
		"scope":            "tenant",
		"namespace":        "ns",
		"tenantId":         "t-1",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ClassifierFlatMap() = %#v, want %#v", got, want)
	}
}

// customKeys must be serialized as a nested "customKeys" object — the canonical
// dbaas-aggregator classifier shape — never flattened onto the top level.
func TestClassifierFlatMap_CustomKeysNestedWithNativeTypes(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		CustomKeys: map[string]apiextensionsv1.JSON{
			"logicalDBName": jsonVal(`"configs"`),
			"shardCount":    jsonVal(`5`),
			"enabled":       jsonVal(`true`),
			"tags":          jsonVal(`["a","b"]`),
			"meta":          jsonVal(`{"region":"us-east","zone":"a"}`),
		},
	})

	// Top level carries only the identity scalars plus the nested customKeys.
	if _, leaked := got["logicalDBName"]; leaked {
		t.Fatalf("customKeys leaked to top level: %#v", got)
	}

	ck, ok := got["customKeys"].(map[string]any)
	if !ok {
		t.Fatalf("customKeys must be a nested map[string]any, got %T", got["customKeys"])
	}
	want := map[string]any{
		"logicalDBName": "configs",
		"shardCount":    float64(5), // json.Unmarshal decodes numbers as float64
		"enabled":       true,
		"tags":          []any{"a", "b"},
		"meta":          map[string]any{"region": "us-east", "zone": "a"},
	}
	if !reflect.DeepEqual(ck, want) {
		t.Fatalf("customKeys = %#v, want %#v", ck, want)
	}
}

func TestClassifierFlatMap_OmitsEmptyOptionalFields(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		CustomKeys:       map[string]apiextensionsv1.JSON{}, // empty → omitted
	})
	for _, k := range []string{"namespace", "tenantId", "customKeys"} {
		if _, present := got[k]; present {
			t.Fatalf("expected key %q to be omitted, got %#v", k, got)
		}
	}
}

func TestClassifierFlatMap_RoundTripsThroughJSON(t *testing.T) {
	in := Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		Namespace:        "ns",
		CustomKeys: map[string]apiextensionsv1.JSON{
			"shardCount": jsonVal(`5`),
			"meta":       jsonVal(`{"region":"us-east"}`),
		},
	}
	encoded, err := json.Marshal(ClassifierFlatMap(in))
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	var out map[string]any
	if err := json.Unmarshal(encoded, &out); err != nil {
		t.Fatalf("json.Unmarshal: %v", err)
	}
	ck, ok := out["customKeys"].(map[string]any)
	if !ok {
		t.Fatalf("customKeys must survive a JSON round-trip as a nested object, got %T", out["customKeys"])
	}
	if ck["shardCount"] != float64(5) {
		t.Fatalf("shardCount = %#v, want 5", ck["shardCount"])
	}
	if !reflect.DeepEqual(ck["meta"], map[string]any{"region": "us-east"}) {
		t.Fatalf("meta = %#v, want {region:us-east}", ck["meta"])
	}
}
