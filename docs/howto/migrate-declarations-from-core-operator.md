# Migrating `DbPolicy` and `DatabaseDeclaration` from Core Operator to DBaaS Operator

The legacy **Core Operator** consumed DBaaS declarations as generic
`kind: DBaaS` resources (with a `subKind`) and forwarded them to
dbaas-aggregator's declarative API (`POST /api/declarations/v1/apply`).

The **DBaaS Operator** replaces those generic declarations with **native CRDs**
on the `dbaas.netcracker.com/v1` API group. The data sent to the aggregator is
the same — only the resource you author in Kubernetes changes.

This guide shows how to convert each declaration type. The aggregator-side
behavior (role grants, provisioning, cloning) is unchanged.

---

## What changes for every declaration

| Aspect | Core Operator (old) | DBaaS Operator (new) |
|---|---|---|
| `apiVersion` | `core.netcracker.com/v1` | `dbaas.netcracker.com/v1` |
| `kind` / `subKind` | `kind: DBaaS` + `subKind: DbPolicy\|DatabaseDeclaration` | `kind: DbPolicy` / `kind: DatabaseDeclaration` (no `subKind`) |
| `spec.apiVersion: v1` | present (declaration version) | **removed** — not part of the CRD |
| Owning microservice | derived from label `app.kubernetes.io/name` (fallback `app.kubernetes.io/instance`) | **explicit field in `spec`** (see per-type sections) |
| Labels | `app.kubernetes.io/instance`, `app.kubernetes.io/managed-by: operator` | `app.kubernetes.io/name` recommended; `managed-by` no longer required |

> **Why `microserviceName` moves into `spec`.** Core Operator read the owning
> service from the `app.kubernetes.io/name` label and injected it into the
> declaration's `metadata.microserviceName`. The new CRDs make it an explicit,
> validated, **immutable** spec field so the owner is unambiguous and auditable.

---

## DbPolicy

### Field mapping

| Old (`subKind: DbPolicy`) | New (`kind: DbPolicy`) |
|---|---|
| label `app.kubernetes.io/name` / `app.kubernetes.io/instance` | `spec.microserviceName` (**required, immutable**) |
| `spec.services[].name` / `.roles[]` | `spec.services[].name` / `.roles[]` — unchanged |
| `spec.policy[].type` / `.defaultRole` / `.additionalRole[]` | `spec.policy[]` — unchanged |
| — | `spec.disableGlobalPermissions` (new, optional, default `false`) |

### Before (Core Operator)

```yaml
apiVersion: core.netcracker.com/v1
kind: DBaaS
subKind: DbPolicy
metadata:
  name: {{ .Values.SERVICE_NAME }}-dbPolicy
  namespace: {{ .Values.NAMESPACE }}
  labels:
    app.kubernetes.io/instance: {{ .Values.SERVICE_NAME }}
    app.kubernetes.io/managed-by: operator
spec:
  apiVersion: v1
  services:
    - name: install-base-service
      roles:
        - admin
    - name: cdc-streaming-platform
      roles:
        - streaming
  policy:
    - type: postgresql
      defaultRole: admin
      additionalRole: []
    - type: opensearch
      defaultRole: admin
      additionalRole: []
```

### After (DBaaS Operator)

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DbPolicy
metadata:
  name: {{ .Values.SERVICE_NAME }}-dbpolicy
  namespace: {{ .Values.NAMESPACE }}
  labels:
    app.kubernetes.io/name: {{ .Values.SERVICE_NAME }}
spec:
  microserviceName: {{ .Values.SERVICE_NAME }}   # was the app.kubernetes.io/instance label
  services:
    - name: install-base-service
      roles:
        - admin
    - name: cdc-streaming-platform
      roles:
        - streaming
  policy:
    - type: postgresql
      defaultRole: admin
      additionalRole: []
    - type: opensearch
      defaultRole: admin
      additionalRole: []
```

---

## DatabaseDeclaration

The biggest structural change: the old `spec.declarations` is an **array**, so a
single legacy resource could declare several databases. The new CRD describes
**exactly one** database. **Split each entry of `spec.declarations[]` into its
own `DatabaseDeclaration` CR.**

### Field mapping

| Old (`subKind: DatabaseDeclaration`) | New (`kind: DatabaseDeclaration`) |
|---|---|
| `spec.declarations[]` (array) | one CR **per array entry** |
| `declarations[].classifierConfig.classifier{...}` | `spec.classifier{...}` (the `classifierConfig` wrapper is dropped) |
| label `app.kubernetes.io/name` / `app.kubernetes.io/instance` | `spec.classifier.microserviceName` (**required, immutable**) |
| `classifier.scope` | `spec.classifier.scope` (**required**) |
| `classifier.customKeys{...}` | `spec.classifier.customKeys{...}` — unchanged |
| `declarations[].type` | `spec.type` (**required, immutable**) |
| `declarations[].versioningConfig.approach` | `spec.versioningConfig.approach` |
| `declarations[].initialInstantiation.approach` | `spec.initialInstantiation.approach` |
| `initialInstantiation.sourceClassifier{...}` | `spec.initialInstantiation.sourceClassifier{...}` — now a full `Classifier` (add `microserviceName`) |
| `declarations[].lazy` / `.settings` / `.namePrefix` | `spec.lazy` / `spec.settings` / `spec.namePrefix` |

### Before (Core Operator)

```yaml
apiVersion: core.netcracker.com/v1
kind: DBaaS
subKind: DatabaseDeclaration
metadata:
  name: {{ .Values.SERVICE_NAME }}-<dbConfigName>
  namespace: {{ .Values.NAMESPACE }}
  labels:
    app.kubernetes.io/instance: {{ .Values.SERVICE_NAME }}
    app.kubernetes.io/managed-by: operator
spec:
  apiVersion: v1
  declarations:
    - classifierConfig:
        classifier:
          scope: service
          customKeys:
            logicalDBName: configs
      type: postgresql
      versioningConfig:
        approach: clone
      initialInstantiation:
        approach: clone
        sourceClassifier:
          scope: service
```

### After (DBaaS Operator)

One CR per `declarations[]` entry:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseDeclaration
metadata:
  name: {{ .Values.SERVICE_NAME }}-configs   # one stable name per database
  namespace: {{ .Values.NAMESPACE }}
  labels:
    app.kubernetes.io/name: {{ .Values.SERVICE_NAME }}
spec:
  classifier:
    microserviceName: {{ .Values.SERVICE_NAME }}   # was the app.kubernetes.io/instance label
    scope: service
    customKeys:
      logicalDBName: configs
  type: postgresql
  versioningConfig:
    approach: clone
  initialInstantiation:
    approach: clone
    sourceClassifier:
      microserviceName: {{ .Values.SERVICE_NAME }} # must match classifier.microserviceName
      scope: service
```

> `initialInstantiation.sourceClassifier` is now a full `Classifier`. The legacy
> form often carried only `scope`; you must add `microserviceName`, and it must
> equal `spec.classifier.microserviceName`.

---

## Gotchas

- **Immutable fields.** `DbPolicy.spec.microserviceName` and
  `DatabaseDeclaration.spec.classifier` + `spec.type` are immutable after
  creation (enforced by CEL validation). To repoint a CR at a different service
  or database, **delete and recreate** it rather than editing in place.
- **`classifier.namespace`.** Optional. If omitted, the aggregator defaults it
  to the CR's `metadata.namespace`. If you set it, it **must equal**
  `metadata.namespace`, otherwise the controller reports `InvalidConfiguration`.
- **No more `subKind` / `spec.apiVersion`.** Remove both; they have no place in
  the CRDs. The aggregator still receives them internally (the operator fills
  `kind: DBaaS` / `subKind` on the wire), so you don't need to.
- **One database per `DatabaseDeclaration`.** There is no `declarations[]` array;
  fan a multi-entry legacy resource out into multiple CRs with distinct names.
- **`clone` requires a source.** When `initialInstantiation.approach: clone`,
  `sourceClassifier` is required and `spec.lazy: true` is prohibited.
- **Status & lifecycle.** Each CR now carries its own `status.phase`,
  conditions, and `observedGeneration`; provisioning is asynchronous and the
  controller polls the aggregator. See the runbook for the phase/condition
  reference.

---

## Migration checklist

For each legacy `kind: DBaaS` resource:

1. [ ] Note the owning service from the `app.kubernetes.io/name` /
       `app.kubernetes.io/instance` label.
2. [ ] Change `apiVersion` to `dbaas.netcracker.com/v1` and replace
       `kind: DBaaS` + `subKind: X` with `kind: X`.
3. [ ] Delete `spec.apiVersion`.
4. [ ] **DbPolicy:** add `spec.microserviceName` with the value from step 1.
5. [ ] **DatabaseDeclaration:** split `spec.declarations[]` into one CR each;
       unwrap `classifierConfig.classifier` into `spec.classifier`; add
       `spec.classifier.microserviceName` from step 1; add
       `microserviceName` to every `sourceClassifier`.
6. [ ] Set the label `app.kubernetes.io/name` to the owning service; drop
       `app.kubernetes.io/managed-by: operator`.
7. [ ] Apply, then verify `status.phase: Succeeded` (and `Ready=True`) on each CR.
