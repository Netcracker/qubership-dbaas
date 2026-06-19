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

package poller

import (
	"context"
	"encoding/json"
	"testing"

	apiextensionsv1 "k8s.io/apiextensions-apiserver/pkg/apis/apiextensions/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"

	dbaasv1 "github.com/netcracker/qubership-dbaas/dbaas-operator/api/v1"
)

// claimIndexFn mirrors the production field indexer registered in
// DatabaseSecretClaimReconciler.SetupWithManager so the fake client resolves
// MatchingFields the same way the real cache does.
func claimIndexFn(obj client.Object) []string {
	ds := obj.(*dbaasv1.DatabaseSecretClaim)
	c := dbaasv1.EffectiveClassifier(ds.Spec.Classifier, ds.Namespace)
	return []string{dbaasv1.ClassifierIndexKey(c, ds.Spec.Type)}
}

func newFakeClient(objs ...client.Object) client.Client {
	scheme := runtime.NewScheme()
	if err := dbaasv1.AddToScheme(scheme); err != nil {
		panic(err)
	}
	return fake.NewClientBuilder().
		WithScheme(scheme).
		WithObjects(objs...).
		WithIndex(&dbaasv1.DatabaseSecretClaim{}, dbaasv1.ClassifierTypeIndex, claimIndexFn).
		Build()
}

func TestPatchClaimsForRotation_PatchesMatchingClaim(t *testing.T) {
	claim := &dbaasv1.DatabaseSecretClaim{
		ObjectMeta: metav1.ObjectMeta{Name: "c1", Namespace: "ns"},
		Spec: dbaasv1.DatabaseSecretClaimSpec{
			Classifier: dbaasv1.Classifier{MicroserviceName: "ms", Scope: "service", Namespace: "ns"},
			Type:       "postgresql",
		},
	}
	cl := newFakeClient(claim)
	classifier := map[string]any{"microserviceName": "ms", "scope": "service", "namespace": "ns"}

	matched, patched, err := PatchClaimsForRotation(
		context.Background(), cl, "ns", classifier, "postgresql", "2026-06-16T12:00:00Z")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if matched != 1 || patched != 1 {
		t.Fatalf("matched=%d patched=%d, want 1/1", matched, patched)
	}

	var got dbaasv1.DatabaseSecretClaim
	if err := cl.Get(context.Background(), client.ObjectKey{Namespace: "ns", Name: "c1"}, &got); err != nil {
		t.Fatalf("get patched claim: %v", err)
	}
	if v := got.Annotations[dbaasv1.AnnotationRotationTrigger]; v != "2026-06-16T12:00:00Z" {
		t.Errorf("rotation-trigger annotation = %q, want the supplied value", v)
	}
}

// A classifier carrying an extra top-level field (spec.classifier.extraKeys,
// flattened on the wire) must match the DatabaseSecretClaim indexed with the
// same extraKeys — the poller routes unknown top-level keys back into ExtraKeys
// so the round-trip reproduces the controller's index key.
func TestPatchClaimsForRotation_MatchesExtraKeysClaim(t *testing.T) {
	claim := &dbaasv1.DatabaseSecretClaim{
		ObjectMeta: metav1.ObjectMeta{Name: "c1", Namespace: "ns"},
		Spec: dbaasv1.DatabaseSecretClaimSpec{
			Classifier: dbaasv1.Classifier{
				MicroserviceName: "ms", Scope: "service", Namespace: "ns",
				ExtraKeys: map[string]apiextensionsv1.JSON{"region": {Raw: []byte(`"eu"`)}},
			},
			Type: "postgresql",
		},
	}
	cl := newFakeClient(claim)

	// The aggregator feed delivers the classifier flat: region at the top level.
	classifier := map[string]any{
		"microserviceName": "ms", "scope": "service", "namespace": "ns", "region": "eu",
	}
	matched, patched, err := PatchClaimsForRotation(
		context.Background(), cl, "ns", classifier, "postgresql", "v")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if matched != 1 || patched != 1 {
		t.Fatalf("matched=%d patched=%d, want 1/1 — extraKeys must participate in the index", matched, patched)
	}

	// The same feed without the extra field must NOT match the extraKeys claim:
	// the extra field is part of the identity.
	matched, _, err = PatchClaimsForRotation(
		context.Background(), cl, "ns",
		map[string]any{"microserviceName": "ms", "scope": "service", "namespace": "ns"},
		"postgresql", "v")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if matched != 0 {
		t.Fatalf("matched=%d, want 0 — a classifier missing the extra field must not match", matched)
	}
}

// A flattened extraKey literally named "extraKeys" with a scalar value must not
// break the poller. classifierFromMap routes it into ExtraKeys; a struct
// json.Unmarshal would instead try to decode the scalar against the ExtraKeys
// map field, error out, and skip the whole rotation event.
func TestPatchClaimsForRotation_LiteralExtraKeysWireKey(t *testing.T) {
	claim := &dbaasv1.DatabaseSecretClaim{
		ObjectMeta: metav1.ObjectMeta{Name: "c1", Namespace: "ns"},
		Spec: dbaasv1.DatabaseSecretClaimSpec{
			Classifier: dbaasv1.Classifier{
				MicroserviceName: "ms", Scope: "service", Namespace: "ns",
				ExtraKeys: map[string]apiextensionsv1.JSON{"extraKeys": {Raw: []byte(`"foo"`)}},
			},
			Type: "postgresql",
		},
	}
	cl := newFakeClient(claim)
	classifier := map[string]any{
		"microserviceName": "ms", "scope": "service", "namespace": "ns", "extraKeys": "foo",
	}
	matched, patched, err := PatchClaimsForRotation(
		context.Background(), cl, "ns", classifier, "postgresql", "v")
	if err != nil {
		t.Fatalf("a scalar \"extraKeys\" wire key must not break reverse mapping: %v", err)
	}
	if matched != 1 || patched != 1 {
		t.Fatalf("matched=%d patched=%d, want 1/1", matched, patched)
	}
}

// A large integer in a classifier identity field must round-trip through the
// poller without precision loss. The client decodes the changed feed with
// UseNumber, so the value arrives as json.Number; classifierFromMap must keep it
// exact so the index key matches the controller side.
func TestPatchClaimsForRotation_LargeIntegerExtraKey(t *testing.T) {
	const big = "9007199254740993" // 2^53 + 1
	claim := &dbaasv1.DatabaseSecretClaim{
		ObjectMeta: metav1.ObjectMeta{Name: "c1", Namespace: "ns"},
		Spec: dbaasv1.DatabaseSecretClaimSpec{
			Classifier: dbaasv1.Classifier{
				MicroserviceName: "ms", Scope: "service", Namespace: "ns",
				ExtraKeys: map[string]apiextensionsv1.JSON{"accountId": {Raw: []byte(big)}},
			},
			Type: "postgresql",
		},
	}
	cl := newFakeClient(claim)
	classifier := map[string]any{
		"microserviceName": "ms", "scope": "service", "namespace": "ns",
		"accountId": json.Number(big),
	}
	matched, _, err := PatchClaimsForRotation(
		context.Background(), cl, "ns", classifier, "postgresql", "v")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if matched != 1 {
		t.Fatalf("matched=%d, want 1 — a large-integer extraKey must round-trip exactly", matched)
	}
}

func TestPatchClaimsForRotation_NoMatchingClaim(t *testing.T) {
	cl := newFakeClient()
	matched, patched, err := PatchClaimsForRotation(
		context.Background(), cl, "ns",
		map[string]any{"microserviceName": "absent", "scope": "service", "namespace": "ns"},
		"postgresql", "v")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if matched != 0 || patched != 0 {
		t.Errorf("matched=%d patched=%d, want 0/0", matched, patched)
	}
}
