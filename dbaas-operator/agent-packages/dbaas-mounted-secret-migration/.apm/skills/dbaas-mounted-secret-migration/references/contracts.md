# DBaaS declarative and mounted-secret contracts

Use this reference when generating or reviewing `InternalDatabase`, `DatabaseSecretClaim`, and
workload mounts.

## Identity model

The database identity is the complete classifier plus database type. The mounted-secret client key
adds the requested role:

```text
database identity = canonical(classifier) | lowercase(type)
mounted lookup    = canonical(classifier) | lowercase(type) | trim(requested role)
```

Classifier equality includes extension keys and their JSON values. Key order does not matter; a
missing key, extra key, different value, or nested-vs-top-level placement does matter.

The operator defaults an omitted classifier namespace from `metadata.namespace` before calling the
aggregator and writing Secret metadata. The running client normally enriches an omitted namespace
from `microservice.namespace`. Both sides must resolve to the same value.

## Operator namespace (issue #776)

`spec.operatorNamespace` on every generated CR is the namespace of the `dbaas-operator` instance
that must manage it -- never the workload namespace, and never assumed to be `dbaas-system`. The
writer never registers a default for it (no `values.yaml`/`values.schema.json` edits, ever); the
plan's `operatorNamespace` must already be a concrete, verified value or Helm expression.

For a Helm chart, when `API_DBAAS_ADDRESS` (e.g. `http://dbaas-aggregator.dbaas-operator:8080`) is
a namespaced Kubernetes in-cluster service address, use the second DNS label of its host as the
operator namespace:

```gotemplate
{{ (index (splitList "." (first (splitList ":" (last (splitList "://" $.Values.API_DBAAS_ADDRESS))))) 1) }}
```

`http://dbaas-aggregator.dbaas-operator:8080` -> host `dbaas-aggregator.dbaas-operator` -> second
label `dbaas-operator`. `http://dbaas-aggregator.dbaas:8080` -> `dbaas`. This only identifies the
namespace for an address of the in-cluster `<service>.<namespace>[.svc[.cluster.local]]` shape --
`index` on the split label list fails to render (out of range) for an external, single-label, or
empty address, which is the correct outcome: never fall back to a guessed or hardcoded namespace.

Rules:

- if a chart already pins `DBAAS_OPERATOR_NAMESPACE` (or any other value key the operator
  namespace expression resolves through) to a real value, use it as-is; an explicitly chosen empty
  value must fail validation, never be masked with a synthesized non-empty one;
- for a plain manifest (no Helm templating available), resolve a concrete literal namespace
  instead: prefer an explicit deployment value, verify it against the intended `dbaas-operator`
  Deployment/Pod when a cluster is available, and stop and ask if it cannot be proven -- never
  assume `dbaas-system` or reuse the workload namespace;
- when `API_DBAAS_ADDRESS` is external, single-label, empty, or otherwise cannot identify the
  operator namespace, require an explicit, verified literal in the plan instead of the expression.

## Capability guard (issue #776)

Mixed clusters may not have the dbaas-operator CRDs installed at all. The plan's root-level
`capabilityGuard` field (optional; a capability string such as `"dbaas.netcracker.com/v1"`) makes
every generated resource and inserted volume/mount conditional on that capability being present,
instead of assuming the operator is always installed:

```gotemplate
{{- if .Capabilities.APIVersions.Has "dbaas.netcracker.com/v1" }}
... InternalDatabase / DatabaseSecretClaim / DatabaseAccessPolicy ...
{{- end }}
```

Omitting `capabilityGuard` preserves the unwrapped, operator-only output exactly as before. When it
is set:

- every generated resource in the output file, and every newly inserted Secret volume/volumeMount
  in a workload manifest, is wrapped in the guard above;
- the optional root-level `operatorModeEnvironment` field (`{"name": "DBAAS_OPERATOR_ENABLED",
  "value": "true"}`) inserts that environment variable into every target container, under the same
  guard -- the one signal a custom Go provider (see `references/frameworks/go.md`) uses to tell
  operator mode from legacy mode; it requires `capabilityGuard` to be set;
- a superseded legacy declaration is preserved -- its original bytes, comments and labels included
  -- guarded to the operator-absent (negated) branch, instead of being deleted:
  ```gotemplate
  {{- if not (.Capabilities.APIVersions.Has "dbaas.netcracker.com/v1") }}
  ... original legacy declaration bytes, verbatim ...
  {{- end }}
  ```

The writer's own `--check`/`--apply` validation renders the chart twice when `capabilityGuard` is
set: once with `--api-versions <capabilityGuard>` (the primary gate -- the branch the generated
resources actually live in must be well-formed and match the inventory), and once without (the
existing `validate-operator-absent-fallback` check -- the native resources and the operator-mode
env var must be completely absent from that render, proving the guard actually suppresses them).

## Classifier mapping

Use typed fields for the standard identity:

```yaml
classifier:
  microserviceName: orders
  namespace: orders-ns
  scope: service
```

Add `tenantId` only when it is part of a deployment-known tenant identity.

Preserve a runtime top-level extension with `extraKeys`:

```yaml
classifier:
  microserviceName: catalog
  namespace: catalog-ns
  scope: service
  extraKeys:
    dbClassifier: default
```

The operator flattens `extraKeys`, producing runtime wire identity
`{"dbClassifier":"default", ...}`. Do not put a top-level runtime key under `customKeys`; that
would instead produce `{"customKeys":{"dbClassifier":"default"}, ...}` and identify a different
database.

Use `customKeys` only when the runtime classifier itself contains a nested `customKeys` object.
Never repeat reserved keys (`microserviceName`, `scope`, `namespace`, `tenantId`, `customKeys`) in
`extraKeys`.

## InternalDatabase template

Replace `<operator-namespace>` with the verified namespace of the operator instance that must manage
the generated CRs. It is not necessarily the workload namespace.

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: orders-postgresql-service-db
  namespace: orders-ns
spec:
  operatorNamespace: <operator-namespace>
  classifier:
    microserviceName: orders
    namespace: orders-ns
    scope: service
  type: postgresql
  lazy: false
```

Optional mappings supported by the current CR contract are:

- `BaseDbParams.NamePrefix` to `spec.namePrefix`;
- JSON-valued database creation settings to `spec.settings` without changing their types;
- explicitly configured versioning and initial-instantiation behavior to their corresponding
  structures.

Do not copy client connection-pool, migration, retry, or datasource settings into `spec.settings`.
The CR type is `map[string]apiextensionsv1.JSON`; values may be strings, numbers, booleans, null,
arrays, or nested objects. Preserve the original JSON value instead of stringifying it.
Do not silently drop `PhysicalDatabaseId`; block that identity until a target-contract mapping is
confirmed.

## DatabaseSecretClaim template

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseSecretClaim
metadata:
  name: orders-postgresql-service-default-claim
  namespace: orders-ns
  labels:
    app.kubernetes.io/name: orders
spec:
  operatorNamespace: <operator-namespace>
  classifier:
    microserviceName: orders
    namespace: orders-ns
    scope: service
  type: postgresql
  userRole: ""
  secretName: orders-postgresql-service-default-credentials
```

The label is mandatory in the current operator controller and is sent as `originService` in the
aggregator get-by-classifier request.

Set `userRole` to the exact role passed by the client. Empty and explicit `admin` are different
mounted lookup keys even if the aggregator eventually resolves both to administrator credentials.
Create separate claims and mounts when one database is requested with multiple roles.

## Helm-templated workloads (issue #776)

`apply_migration.py` edits a workload manifest by byte-span insertion, never a full re-dump, so
comments, key order, and every untouched line survive exactly. It never rejects a workload outright
for containing a standalone Helm action (`if`/`else`/`range`/`with`/`end`/`define`/`block`/
`template`, or a `{{- $x := ... }}` assignment) or an unquoted templated scalar
(`name: {{ .Values.X }}`): it composes a length-preserving masked copy (a standalone action line
blanked to spaces, an inline expression replaced with same-length filler) purely to locate the
static workload/pod-spec/container/volume/mount nodes it needs, then splices the new content into
the *original*, unmasked text at those same offsets. Every original line outside an inserted span is
byte-for-byte unchanged, guard lines included.

After editing, the result is re-verified (not merely trusted): every newly inserted volume and mount
must still be reachable, by name, under the right container in the edited text. This is the precise
blocking path for a structure that cannot be edited safely -- for example a `range` that generates
the *entire* target container list with no static entry to find by name. It cannot prove every
possible case (a conditional that wraps the entire target key/sequence, not just unrelated content
next to it, can still look reachable under masking, since YAML tolerates a blank line -- what the
conditional's closing action becomes -- between sequence items); that shape is instead caught by the
real `helm template` render this writer runs before ever committing, against the chart's actual
values, which correctly omits anything a false condition would omit.

The operator creates an opaque Secret with two data keys:

```text
metadata.json
connectionProperties.json
```

`metadata.json` carries the canonical classifier, type, requested `userRole`, and descriptive
database fields. `connectionProperties.json` carries the adapter response. Mount the Secret as a
directory at:

```text
/etc/secrets/dbaas-secrets/<DatabaseSecretClaim.spec.secretName>
```

The base client scans immediate subdirectories of `/etc/secrets/dbaas-secrets`. It does not infer a
classifier from the directory name.

## Runtime behavior

In a compatible base client, `NewDbaaSPool` includes the mounted-secret provider in its logical DB
provider chain. `GetOrCreateDb` and `GetConnection` first try providers and call the DBaaS REST API
only after every provider misses.

Therefore, prove all three points before claiming a successful migration:

1. the resolved client contains and registers the provider;
1. the mounted Secret metadata produces exactly the requested lookup key;
1. logs or an isolated fallback endpoint prove that the successful request did not reach REST.
