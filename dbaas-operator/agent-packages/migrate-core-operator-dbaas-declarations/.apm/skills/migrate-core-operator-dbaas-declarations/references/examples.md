# DBaaS migration examples

## JSON DatabaseDeclaration to InternalDatabase

Before:

```json
{
  "apiVersion": "nc.core.dbaas/v3",
  "kind": "DatabaseDeclaration",
  "declarations": [
    {
      "classifierConfig": {
        "classifier": {
          "scope": "service",
          "microserviceName": "{{$SERVICE_NAME}}",
          "customKeys": {
            "logicalDBName": "configs"
          }
        }
      },
      "type": "postgresql",
      "settings": {
        "pgExtensions": ["vector"]
      },
      "versioningConfig": {
        "approach": "clone"
      }
    }
  ]
}
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: configs-db
  namespace: "{{ .Values.NAMESPACE }}"
spec:
  operatorNamespace: "dbaas-system"
  classifier:
    scope: service
    microserviceName: "{{ .Values.SERVICE_NAME }}"
    customKeys:
      logicalDBName: configs
  type: postgresql
  settings:
    pgExtensions:
      - vector
  versioningConfig:
    approach: clone
```

## YAML DBaaS wrapper variant

This repeats the same field conversion for generic YAML CR input. Drop the `kind: DBaaS`/`subKind` wrapper and
its wrapper-only Core labels.

Before:

```yaml
apiVersion: core.netcracker.com/v1
kind: DBaaS
subKind: DatabaseDeclaration
metadata:
  name: db-declaration-1
  namespace: "{{ .Values.NAMESPACE }}"
  labels:
    app.kubernetes.io/instance: "{{ .Values.SERVICE_NAME }}"
spec:
  classifierConfig:
    classifier:
      scope: service
      microserviceName: "{{ .Values.SERVICE_NAME }}"
  lazy: false
  type: postgresql
  initialInstantiation:
    approach: clone
    sourceClassifier:
      scope: service
      microserviceName: "{{ .Values.SERVICE_NAME }}"
      customKeys:
        logicalDbName: source-db
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: db-declaration-1
  namespace: "{{ .Values.NAMESPACE }}"
spec:
  operatorNamespace: "dbaas-system"
  classifier:
    scope: service
    microserviceName: "{{ .Values.SERVICE_NAME }}"
  type: postgresql
  initialInstantiation:
    approach: clone
    sourceClassifier:
      scope: service
      microserviceName: "{{ .Values.SERVICE_NAME }}"
      customKeys:
        logicalDbName: source-db
```

## Extra classifier keys

Before:

```json
{
  "classifierConfig": {
    "classifier": {
      "scope": "service",
      "microserviceName": "dbaas-spring-service",
      "transactional": true
    }
  },
  "type": "postgresql"
}
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: service-db
spec:
  operatorNamespace: "dbaas-system"
  classifier:
    scope: service
    microserviceName: dbaas-spring-service
    extraKeys:
      transactional: true
  type: postgresql
```

## JSON DbPolicy to DatabaseAccessPolicy

Before:

```json
{
  "apiVersion": "nc.core.dbaas/v3",
  "kind": "DbPolicy",
  "services": [
    {
      "name": "externalService",
      "roles": ["ro"]
    }
  ],
  "policy": [
    {
      "type": "postgresql",
      "defaultRole": "admin",
      "additionalRole": ["rw", "ro"]
    }
  ],
  "disableGlobalPermissions": "false"
}
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: database-access-policy
  namespace: "{{ .Values.NAMESPACE }}"
spec:
  operatorNamespace: "dbaas-system"
  microserviceName: "{{ .Values.SERVICE_NAME }}"
  services:
    - name: externalService
      roles:
        - ro
  policy:
    - type: postgresql
      defaultRole: admin
      additionalRole:
        - rw
        - ro
  disableGlobalPermissions: false
```

## DGP-only DbPolicy to DatabaseAccessPolicy

A legacy `DbPolicy` carrying only `disableGlobalPermissions` -- no `services`, no `policy` -- is a
valid, complete policy on its own. The converter checks that the field is *present*, not that it is
`true`: an explicit `disableGlobalPermissions: false` converts exactly like `true` does, since a
present `false` is a different, real policy from the field being omitted entirely (which still fails
with "must have a non-empty services or policy list, or disableGlobalPermissions present").

Before:

```json
{
  "apiVersion": "nc.core.dbaas/v3",
  "kind": "DbPolicy",
  "disableGlobalPermissions": false
}
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: database-access-policy
  namespace: "{{ .Values.NAMESPACE }}"
spec:
  operatorNamespace: "dbaas-system"
  microserviceName: "{{ .Values.SERVICE_NAME }}"
  disableGlobalPermissions: false
```

## Wrapper-only labels are dropped, ordinary labels are kept

Before (a `kind: DBaaS` wrapper carrying its own processing-state labels alongside ordinary ones):

```json
{
  "apiVersion": "core.netcracker.com/v1",
  "kind": "DBaaS",
  "subKind": "DbPolicy",
  "metadata": {
    "name": "legacy-policy",
    "labels": {
      "app.kubernetes.io/processed-by-operator": "true",
      "deployer.cleanup/allow": "true",
      "app.kubernetes.io/name": "orders",
      "argocd.argoproj.io/instance": "orders"
    }
  },
  "spec": { "disableGlobalPermissions": true }
}
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: legacy-policy
  namespace: "{{ .Values.NAMESPACE }}"
  labels:
    app.kubernetes.io/name: orders
    argocd.argoproj.io/instance: orders
spec:
  operatorNamespace: "dbaas-system"
  microserviceName: "{{ .Values.SERVICE_NAME }}"
  disableGlobalPermissions: true
```

Only the two wrapper-only labels are dropped; every other label, and every annotation, is preserved. A
direct (non-`kind: DBaaS`) `DbPolicy`/`DatabaseDeclaration` is never filtered this way, even if it happens
to carry a same-named label.

## Split database declaration

Before JSON with two `declarations[]` entries must become two `InternalDatabase` resources:

```json
{
  "apiVersion": "nc.core.dbaas/v3",
  "kind": "DatabaseDeclaration",
  "declarations": [
    {
      "classifierConfig": {
        "classifier": {
          "scope": "service",
          "microserviceName": "{{$SERVICE_NAME}}"
        }
      },
      "type": "postgresql",
      "physicalDatabaseId": "postgresql-prod-a"
    },
    {
      "classifierConfig": {
        "classifier": {
          "scope": "service",
          "microserviceName": "{{$SERVICE_NAME}}",
          "customKeys": {
            "logicalDBName": "configs"
          }
        }
      },
      "type": "postgresql",
      "versioningConfig": {
        "approach": "new"
      }
    }
  ]
}
```

After:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: transactional-db
  namespace: "{{ .Values.NAMESPACE }}"
spec:
  operatorNamespace: "dbaas-system"
  classifier:
    scope: service
    microserviceName: "{{ .Values.SERVICE_NAME }}"
  type: postgresql
  physicalDatabaseId: postgresql-prod-a
---
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
metadata:
  name: configs-db
  namespace: "{{ .Values.NAMESPACE }}"
spec:
  operatorNamespace: "dbaas-system"
  classifier:
    scope: service
    microserviceName: "{{ .Values.SERVICE_NAME }}"
    customKeys:
      logicalDBName: configs
  type: postgresql
  versioningConfig:
    approach: new
```
