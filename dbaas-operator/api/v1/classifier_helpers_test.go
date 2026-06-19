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
	"strings"
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
		"shardCount":    json.Number("5"), // UseNumber keeps the exact literal
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

// extraKeys must be flattened onto the TOP level (legacy open-classifier shape),
// alongside the identity scalars — never nested. Values keep their native JSON
// types.
func TestClassifierFlatMap_ExtraKeysFlattenedToTopLevel(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		ExtraKeys: map[string]apiextensionsv1.JSON{
			"region":     jsonVal(`"eu"`),
			"shardCount": jsonVal(`5`),
			"enabled":    jsonVal(`true`),
			"tags":       jsonVal(`["a","b"]`),
			"meta":       jsonVal(`{"zone":"a"}`),
		},
	})
	want := map[string]any{
		"microserviceName": "svc",
		"scope":            "service",
		"region":           "eu",
		"shardCount":       json.Number("5"), // UseNumber keeps the exact literal
		"enabled":          true,
		"tags":             []any{"a", "b"},
		"meta":             map[string]any{"zone": "a"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ClassifierFlatMap() = %#v, want %#v", got, want)
	}
	if _, nested := got["extraKeys"]; nested {
		t.Fatalf("extraKeys must not appear as a nested key: %#v", got)
	}
}

// A reserved key inside extraKeys must never override the typed field:
// ClassifierFlatMap skips reserved keys, so the typed scalar always wins.
func TestClassifierFlatMap_ExtraKeysSkipReservedKeys(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		Namespace:        "ns",
		ExtraKeys: map[string]apiextensionsv1.JSON{
			"scope":            jsonVal(`"tenant"`),  // reserved → skipped
			"microserviceName": jsonVal(`"hijack"`),  // reserved → skipped
			"namespace":        jsonVal(`"other"`),   // reserved → skipped
			"tenantId":         jsonVal(`"t-x"`),     // reserved → skipped
			"customKeys":       jsonVal(`{"a":"b"}`), // reserved → skipped
			"region":           jsonVal(`"eu"`),      // allowed → kept
		},
	})
	want := map[string]any{
		"microserviceName": "svc",     // typed wins
		"scope":            "service", // typed wins
		"namespace":        "ns",      // typed wins
		"region":           "eu",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ClassifierFlatMap() = %#v, want %#v", got, want)
	}
}

// extraKeys (flat) and customKeys (nested) coexist independently.
func TestClassifierFlatMap_ExtraKeysCoexistWithCustomKeys(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		CustomKeys:       map[string]apiextensionsv1.JSON{"logicalDBName": jsonVal(`"configs"`)},
		ExtraKeys:        map[string]apiextensionsv1.JSON{"region": jsonVal(`"eu"`)},
	})
	if got["region"] != "eu" {
		t.Fatalf("extraKeys region must be top-level, got %#v", got)
	}
	ck, ok := got["customKeys"].(map[string]any)
	if !ok || ck["logicalDBName"] != "configs" {
		t.Fatalf("customKeys must stay nested, got %#v", got["customKeys"])
	}
	if _, leaked := got["logicalDBName"]; leaked {
		t.Fatalf("customKeys leaked to top level: %#v", got)
	}
}

func TestClassifierFlatMap_EmptyExtraKeysOmitted(t *testing.T) {
	got := ClassifierFlatMap(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		ExtraKeys:        map[string]apiextensionsv1.JSON{}, // empty → nothing added
	})
	want := map[string]any{"microserviceName": "svc", "scope": "service"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ClassifierFlatMap() = %#v, want %#v", got, want)
	}
}

// extraKeys participate in the identity index key, deterministically.
func TestClassifierIndexKey_IncludesExtraKeys(t *testing.T) {
	base := Classifier{MicroserviceName: "svc", Scope: "service"}
	withExtra := Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		ExtraKeys:        map[string]apiextensionsv1.JSON{"region": jsonVal(`"eu"`)},
	}

	// An extra key changes the identity.
	if ClassifierIndexKey(base, "postgresql") == ClassifierIndexKey(withExtra, "postgresql") {
		t.Fatal("extraKeys must change the index key")
	}

	// Same content (regardless of Go map ordering) yields the same key —
	// json.Marshal sorts map keys alphabetically.
	a := Classifier{
		MicroserviceName: "svc", Scope: "service",
		ExtraKeys: map[string]apiextensionsv1.JSON{"region": jsonVal(`"eu"`), "tier": jsonVal(`"gold"`)},
	}
	b := Classifier{
		MicroserviceName: "svc", Scope: "service",
		ExtraKeys: map[string]apiextensionsv1.JSON{"tier": jsonVal(`"gold"`), "region": jsonVal(`"eu"`)},
	}
	if ClassifierIndexKey(a, "postgresql") != ClassifierIndexKey(b, "postgresql") {
		t.Fatalf("index key must be order-independent:\n a=%s\n b=%s",
			ClassifierIndexKey(a, "postgresql"), ClassifierIndexKey(b, "postgresql"))
	}

	// Empty extraKeys must not change the key versus omitting them.
	empty := Classifier{
		MicroserviceName: "svc", Scope: "service",
		ExtraKeys: map[string]apiextensionsv1.JSON{},
	}
	if ClassifierIndexKey(base, "postgresql") != ClassifierIndexKey(empty, "postgresql") {
		t.Fatal("empty extraKeys must not change the index key")
	}
}

func TestReservedExtraKeys(t *testing.T) {
	// No extraKeys / no collisions → nil.
	for name, c := range map[string]Classifier{
		"none":         {MicroserviceName: "svc", Scope: "service"},
		"empty":        {MicroserviceName: "svc", Scope: "service", ExtraKeys: map[string]apiextensionsv1.JSON{}},
		"only-allowed": {MicroserviceName: "svc", Scope: "service", ExtraKeys: map[string]apiextensionsv1.JSON{"region": jsonVal(`"eu"`)}},
	} {
		if got := ReservedExtraKeys(c); got != nil {
			t.Fatalf("%s: ReservedExtraKeys() = %#v, want nil", name, got)
		}
	}

	// Collisions → sorted list of offending keys.
	got := ReservedExtraKeys(Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		ExtraKeys: map[string]apiextensionsv1.JSON{
			"scope":      jsonVal(`"tenant"`),
			"customKeys": jsonVal(`{"a":"b"}`),
			"namespace":  jsonVal(`"other"`),
			"region":     jsonVal(`"eu"`), // allowed
		},
	})
	want := []string{"customKeys", "namespace", "scope"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("ReservedExtraKeys() = %#v, want %#v", got, want)
	}
}

// Large integers (> 2^53) must keep their exact literal through ClassifierFlatMap
// and into the index key — a float64 round-trip would silently truncate them and
// change the database identity relative to an external dbaas-client.
func TestClassifierFlatMap_PreservesLargeIntegerPrecision(t *testing.T) {
	const big = "9007199254740993" // 2^53 + 1, not representable exactly as float64
	c := Classifier{
		MicroserviceName: "svc",
		Scope:            "service",
		CustomKeys:       map[string]apiextensionsv1.JSON{"shardId": jsonVal(big)},
		ExtraKeys:        map[string]apiextensionsv1.JSON{"accountId": jsonVal(big)},
	}

	flat := ClassifierFlatMap(c)
	if got := flat["accountId"]; got != json.Number(big) {
		t.Fatalf("extraKey accountId = %#v, want json.Number(%q)", got, big)
	}
	ck, ok := flat["customKeys"].(map[string]any)
	if !ok {
		t.Fatalf("customKeys must be a nested object, got %T", flat["customKeys"])
	}
	if got := ck["shardId"]; got != json.Number(big) {
		t.Fatalf("customKey shardId = %#v, want json.Number(%q)", got, big)
	}

	// The marshaled index key must carry the exact digits, never a truncated or
	// float-formatted number.
	key := ClassifierIndexKey(c, "postgresql")
	if !strings.Contains(key, big) {
		t.Fatalf("index key lost precision: %s", key)
	}
	if strings.Contains(key, "9007199254740992") || strings.Contains(key, "e+") {
		t.Fatalf("index key shows a float64-truncated number: %s", key)
	}
}
