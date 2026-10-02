# DBaaS CR migration mapping

Use this reference to convert old DBaaS declarative formats to the dedicated dbaas-operator CRDs.

## Source formats

Legacy JSON:

- common filename: `dbaas-configuration.json`; locate it by content regardless of its parent directory
- database declarations: `{"apiVersion":"nc.core.dbaas/v3","kind":"DatabaseDeclaration","declarations":[...]}`
- policies: `{"apiVersion":"nc.core.dbaas/v3","kind":"DbPolicy"}` or `{"kind":"dbPolicy"}`
- files may also contain a top-level JSON array mixing both declaration types

Old generic YAML CR:

- `apiVersion: core.netcracker.com/v1`
- `kind: DBaaS`
- `subKind: DatabaseDeclaration` or `subKind: DbPolicy`
- declaration body under `spec`
- Helm-template YAML does not parse as raw YAML because of unquoted `{{ ... }}` scalar expressions
  and standalone action lines (`if`/`else`/`range`/`with`/`end`/`define`/`block`/`template`, or a
  `{{- $x := ... }}` assignment). `apply_migration.py` never edits the source to work around this:
  it composes a length-preserving masked copy (standalone actions blanked to spaces, inline
  expressions replaced with same-length filler) purely to locate each document's byte span, then
  reads that document's actual value from a second, disposable masked copy that single-quotes each
  unquoted templated scalar so it loads as the exact expression text (see `_mask_for_spans` /
  `_mask_for_value`). A whole-document `{{- if ... }}` / `{{- end }}` guard is preserved verbatim
  around the generated output; a guard that does not bracket the entire document, or YAML that is
  malformed for a reason unrelated to Helm, blocks the run (exit 4) instead of falling back to a
  converter pass that needs manual review.

## Capability guard (issue #776)

A mixed cluster may not have the dbaas-operator CRDs installed. The plan's optional root-level
`capabilityGuard` field (a capability string such as `"dbaas.netcracker.com/v1"`) makes the
generated resource conditional on that capability being present, instead of assuming the operator
is always installed:

```gotemplate
{{- if .Capabilities.APIVersions.Has "dbaas.netcracker.com/v1" }}
... InternalDatabase / DatabaseAccessPolicy ...
{{- end }}
```

nested inside any pre-existing whole-document guard the legacy source already carried (both
conditions apply). Omitting `capabilityGuard` preserves the unwrapped, operator-only output exactly
as before.

When it is set, the legacy source is preserved -- its original bytes, comments and labels included
-- instead of deleted, guarded to the operator-absent (negated) branch in place:

```gotemplate
{{- if not (.Capabilities.APIVersions.Has "dbaas.netcracker.com/v1") }}
... original legacy declaration bytes, verbatim ...
{{- end }}
```

The writer's own `--check`/`--apply` validation renders the chart twice when `capabilityGuard` is
set: once with `--api-versions <capabilityGuard>` (the branch the generated resource actually lives
in must render and pass the same target-CRD validation as always -- a guard that never evaluates
true, e.g. from a typo, is itself flagged, since a render with nothing to validate would otherwise
pass trivially), and once without (the generated resource must be completely absent from that
render, proving the guard actually suppresses it rather than merely being present as inert text).

Target CRDs:

- `apiVersion: dbaas.netcracker.com/v1`
- `kind: InternalDatabase`
- `kind: DatabaseAccessPolicy`

## DatabaseDeclaration to InternalDatabase

Each old database declaration becomes one `InternalDatabase`.

| Old field | New field | Notes |
| --- | --- | --- |
| `declarations[]` | one CR per item | split the list; append the item index when a multi-item wrapper has one parent name |
| `kind: DatabaseDeclaration` | `kind: InternalDatabase` | remove old `kind` and `subKind` |
| operator assignment | `spec.operatorNamespace` | required; supply the namespace of the dbaas-operator instance explicitly |
| `spec.classifierConfig.classifier` or `classifierConfig.classifier` | `spec.classifier` | unwrap `classifierConfig` |
| `classifier.microserviceName` | `spec.classifier.microserviceName` | preserve Helm templates |
| `classifier.scope` | `spec.classifier.scope` | required |
| `classifier.namespace` | omit | the operator derives it from `metadata.namespace`; preserve `sourceClassifier.namespace` separately |
| `classifier.tenantId` | `spec.classifier.tenantId` | a concrete tenant triggers eager materialization; omission leaves only the tenant-agnostic template |
| `classifier.customKeys` | `spec.classifier.customKeys` | preserve nested JSON/YAML values |
| other top-level classifier keys | `spec.classifier.extraKeys` | use for legacy open classifier keys such as `transactional` |
| `type` | `spec.type` | required |
| `lazy` | `spec.lazy` | coerce string `"true"`/`"false"`; flag other non-booleans; do not combine `true` with clone |
| `settings` | `spec.settings` | preserve entries verbatim; each value may be any valid JSON type |
| `namePrefix` | `spec.namePrefix` | optional |
| `physicalDatabaseId` | `spec.physicalDatabaseId` | optional; preserve verbatim, no transformation. Pins only new-creation databases — ignored for `initialInstantiation.approach: clone` and blue-green `versioningConfig.approach: clone`, which follow the source/backup adapter instead |
| `versioningConfig` | `spec.versioningConfig` | marks configuration/versioned database |
| `initialInstantiation` | `spec.initialInstantiation` | optional |
| `initialInstantiation.sourceClassifier` | `spec.initialInstantiation.sourceClassifier` | convert classifier keys; its `microserviceName` must equal the target classifier owner |

Do not keep old `spec.classifierConfig`. The dbaas-operator controller re-wraps `spec.classifier` into the aggregator
wire shape.

## DbPolicy to DatabaseAccessPolicy

Each old DB policy becomes one `DatabaseAccessPolicy`.

| Old field | New field | Notes |
| --- | --- | --- |
| `kind: DbPolicy` or `kind: dbPolicy` | `kind: DatabaseAccessPolicy` | remove old `kind` and `subKind` |
| operator assignment | `spec.operatorNamespace` | required; supply the namespace of the dbaas-operator instance explicitly |
| `services` | `spec.services` | preserve list order |
| `policy` | `spec.policy` | preserve roles and database types |
| `disableGlobalPermissions` | `spec.disableGlobalPermissions` | coerce string `"false"`/`"true"` to boolean when safe |
| owning service name | `spec.microserviceName` | required by new CRD; derive from service context or ask |

Preferred derivation order for `spec.microserviceName`:

1. explicit user-provided service name
2. existing `spec.microserviceName` if the source already has it
3. Helm label `metadata.labels.app.kubernetes.io/instance`
4. Helm expression `{{ .Values.SERVICE_NAME }}` for chart-local declarations
5. service/chart folder name, if clearly the owning service

## Metadata

Use `metadata.name` values that are stable and DNS-label compatible. Examples:

- `db-declaration-1` -> `internaldatabase-1` or a domain-specific name such as `service-db`
- `db-policy-1` -> `database-access-policy` or `fiber-admin-role`

For a multi-item `declarations[]` wrapper with one `metadata.name`, append each declaration index to the parent name.

Use `metadata.namespace` from the old generic CR if present. For Helm charts, preserve:

```yaml
namespace: "{{ .Values.NAMESPACE }}"
```

Do not copy status blocks. `apply_migration.py` carries `metadata.labels` and `metadata.annotations` forward from
the source verbatim -- they are ordinary Kubernetes metadata with no converter-owned mapping, and deployment
tooling may depend on them surviving onto the generated CR.

The one exception is the Core Operator's own `kind: DBaaS` wrapper: it stamps
`app.kubernetes.io/processed-by-operator` and `deployer.cleanup/allow` onto the wrapper's own `metadata.labels`
to track its processing/cleanup state, and those two labels are dropped from the generated native CR (they
describe the wrapper, which no longer exists once the CR is native). Every other label -- application labels,
Argo CD/deployment-tracking labels -- and every annotation are preserved unchanged. A *direct*, non-wrapper
`DatabaseDeclaration`/`DbPolicy` (not carried inside a `kind: DBaaS` envelope) is never filtered this way, even
if it happens to carry a same-named label: the filter only applies to the wrapper's own metadata.

## Validation checklist

- Ensure no output manifest has `kind: DBaaS`.
- Ensure every output manifest has the explicit, correct `spec.operatorNamespace`; do not assume it equals the
  workload namespace.
- Ensure no `InternalDatabase` has `spec.classifierConfig`.
- Omit target `spec.classifier.namespace`; the operator derives it from `metadata.namespace`.
- Ensure every `InternalDatabase` has `spec.classifier.microserviceName`, `spec.classifier.scope`, and `spec.type`.
- Ensure every `DatabaseAccessPolicy` has `spec.microserviceName` and at least one of `spec.services`,
  `spec.policy`, or a *present* `spec.disableGlobalPermissions` field -- `disableGlobalPermissions: false` is
  checked for presence, not truth; an explicit `false` is a valid, distinct policy from the field being absent.
- Flag a `lazy` value that is not boolean after coercing string `"true"`/`"false"`.
- Flag `lazy: true` combined with `initialInstantiation.approach: clone`.
- Flag `initialInstantiation.approach: clone` without `sourceClassifier`.
- Fill a missing `sourceClassifier.microserviceName` from the target classifier and flag any explicit mismatch.
- Reject cross-service clones: source and target `microserviceName` values must be identical.
- Ensure `settings` is an object. Reject non-finite numbers, non-string object keys, and YAML-only values, and
  preserve valid JSON values without conversion.
- Preserve `versioningConfig.approach: clone` or `new`; this is what marks configuration/versioned databases.
