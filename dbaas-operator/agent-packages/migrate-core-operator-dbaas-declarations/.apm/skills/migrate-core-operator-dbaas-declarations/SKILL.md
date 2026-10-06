---
name: migrate-core-operator-dbaas-declarations
description: "Migrate legacy Core Operator DBaaS declarations to dbaas-operator resources. Use only when the user asks to migrate DatabaseDeclaration/DbPolicy resources to InternalDatabase/DatabaseAccessPolicy."
---

# Migrate Core Operator DBaaS declarations

Convert legacy DBaaS declarations into dedicated Kubernetes resources:

- `DatabaseDeclaration` to `apiVersion: dbaas.netcracker.com/v1`, `kind: InternalDatabase`
- `DbPolicy` or `dbPolicy` to `apiVersion: dbaas.netcracker.com/v1`, `kind: DatabaseAccessPolicy`

`scripts/apply_migration.py` is the only process allowed to create, modify, or delete files in the
consumer repository. It is deterministic: given the same plan and repository state it produces the
same result every time. Never hand-edit a migrated file afterward -- if the output needs a change,
change the plan (or the source) and re-run the writer.

## Workflow: inventory, plan, check, apply

1. **Inventory.** Establish the migration scope from user-provided paths, or search the whole
   repository by content when none are given. Find JSON or YAML with `kind: DatabaseDeclaration`,
   `kind: DbPolicy`/`dbPolicy`, or `kind: DBaaS` plus `subKind: DatabaseDeclaration`/`DbPolicy`.
   Common locations include `**/dbaas-configuration.json`, `deployments/`,
   `<service-name>-deployments/`, and Helm chart `templates/`/`declarations/` directories. Group
   what you find by the chart or manifest root that will own its generated output file -- a plan
   may cover more than one root, but each root must target a distinct namespace so duplicate
   resources cannot be hidden across independently validated roots.
2. **Plan.** Build one JSON plan outside the consumer repository (see `scripts/apply_migration.py`'s
   module docstring for the exact shape). For each root, record: the root path (`""`/`"."` for the
   repository root), `"helm"` or `"plain"` kind, `operatorNamespace`/`serviceName`/`namespace`
   (concrete values for a plain root; Helm expressions such as `{{ .Values.NAMESPACE }}` for a helm
   root, plus any `helmValues` the chart needs to render at all), the output file, and every source
   file with the SHA-256 of the exact bytes you inspected. Read [mapping.md](references/mapping.md)
   for the field-by-field conversion this plan feeds into; do not restate it here.
   For a mixed cluster where the dbaas-operator CRDs may not be installed, set the optional
   `capabilityGuard` field (e.g. `"dbaas.netcracker.com/v1"`) so the generated resource only renders
   when that capability is present and the legacy source is preserved -- not deleted -- guarded to
   the operator-absent branch instead (see mapping.md's "Capability guard" section). Omitting it
   keeps every prior behavior unchanged.
3. **Check.** Run `apply_migration.py --repo-root <repo> --plan <plan.json> --check`. This computes
   every change, materializes it into an isolated temporary tree per root, and validates it there --
   including rendering a helm root with `helm template` -- without touching the real repository.
4. **Apply.** Once `--check` reports `valid`, run the same command with `--apply`. Do nothing else
   to the affected files; a warning in the result is not permission to proceed around a block.

Treat the JSON envelope as a draft, not a final answer: `status: "changed"` and exit 0 only mean the
writer did exactly what the plan said, not that the plan said the right thing. Resolve every
converter warning before treating the migration as done -- read what each one describes (a filled-in
or assumed field, a fallback name, a dropped value) against the actual source, and re-run the writer
against a corrected plan if a warning turns out to describe something wrong.

## What the writer accepts and what it blocks

Supported source shapes:

- a JSON file holding one legacy object, or a top-level array mixing `DatabaseDeclaration` and
  `DbPolicy` objects -- removed only when every array element converts; if even one element is
  unsupported or irrelevant, the array is left untouched and the run blocks (never partially
  rewritten);
- a YAML file with one or more `---`-separated documents -- each document is independently either
  fully migrated (removed, along with its own `---` separator) or left byte-for-byte untouched;
  documents are never spliced apart below that granularity;
- a Helm guard is preserved only when it wraps one entire document. Leading comments/blank lines
  before `{{- if ... }}` and trailing comments/blank lines after `{{- end }}` are preserved; a
  guard that does not bracket the whole document blocks the run.

Always blocking, before anything is written:

- a source path used more than once, or equal to a generated output path;
- an unrecognized field on a `DatabaseDeclaration`/`DbPolicy`, a non-boolean `lazy` or
  `disableGlobalPermissions`, a missing `classifierConfig.classifier`, or a `DatabaseAccessPolicy`
  with neither `services` nor `policy` nor a present `disableGlobalPermissions` field -- a legacy
  policy carrying only `disableGlobalPermissions` (`true` **or** `false`) is valid on its own;
  presence of the field is what is checked, not its truth value, since an explicit `false` is a
  real, different policy from the field being omitted entirely;
- a legacy classifier that already has a literal `extraKeys` key (ambiguous against the wire-form
  key this converter introduces);
- a resource's default name that is templated, or mixes literal text with a Helm expression --
  `database_name_hint`'s `<scope>-<type>-db` shape becomes exactly this once `scope` is templated.
  Pin an explicit, release-specific `nameOverrides` entry instead (see the plan shape); it may embed
  a live expression such as `.Release.Name` mixed with literal text -- only an *automatically
  derived* name is restricted to a single whole `{{ ... }}` expression, since only that case cannot
  otherwise be checked at all. The override's rendered value is still checked against every rendered
  resource once the chart is templated;
- a plain root's generated output containing any `{{ ... }}` Helm expression, or a rendered
  Kubernetes name over 63 characters / not a DNS-1123 label;
- two resources in one root computing the same `(kind, namespace, name)`, or two roots targeting
  the same namespace. Independent roots may reuse a name only in distinct namespaces;
- `helm` missing from `PATH` for a helm root (a missing dependency, distinct from a validation
  failure).

## Decisions to make explicitly

- Derive required `DatabaseAccessPolicy.spec.microserviceName` from the owning service only when
  the source context is unambiguous; otherwise ask the user.
- Set `operatorNamespace` to the namespace of the operator instance that will manage the generated
  resources -- ask when that is not known; it is not necessarily the workload namespace, and never
  assume `dbaas-system`. For a Helm root, when the chart exposes a namespaced Kubernetes service
  address as `API_DBAAS_ADDRESS` (e.g. `http://dbaas-aggregator.dbaas-operator:8080`), derive it
  with `{{ (index (splitList "." (first (splitList ":" (last (splitList "://" $.Values.API_DBAAS_ADDRESS))))) 1) }}`
  (the second DNS label of the host) instead of a literal; require an explicit, verified value when
  the address is external, single-label, empty, or otherwise cannot identify the namespace.
- Preserve `physicalDatabaseId` verbatim; it pins only new-creation databases and is ignored for
  `initialInstantiation.approach: clone` and blue-green `versioningConfig.approach: clone`.
- Choose stable, DNS-compatible resource names (or explicit `nameOverrides`) and check for
  duplicate `(kind, namespace, name)` triples across a root before writing the plan.

## Validation

`--check` already performs structural, field, and (for a helm root) rendered-name validation. Beyond
that:

- When current CRD files are available, validate the rendered manifests against their OpenAPI
  schemas.
- When a suitable isolated cluster is also available, optionally run
  `kubectl apply --dry-run=server`; apply for real only when the user requests deployment, then
  verify `status.phase: Succeeded` and `Ready=True`.

Cluster access is optional and must not block the migration -- state plainly which of these
follow-up checks remain pending when they were not run.
