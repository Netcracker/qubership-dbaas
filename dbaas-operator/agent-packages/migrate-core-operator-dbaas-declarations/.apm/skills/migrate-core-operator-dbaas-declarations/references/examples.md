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
  operatorNamespace: '{{ index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1 }}'
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
  operatorNamespace: '{{ index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1 }}'
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

This block uses the plain-manifest form of `operatorNamespace`. A chart-local conversion uses the
`API_DBAAS_ADDRESS` expression shown in the other examples instead.

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
  operatorNamespace: '{{ index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1 }}'
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
  operatorNamespace: '{{ index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1 }}'
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
  operatorNamespace: '{{ index (splitList "." (first (splitList ":" (last (splitList "://" .Values.API_DBAAS_ADDRESS))))) 1 }}'
  classifier:
    scope: service
    microserviceName: "{{ .Values.SERVICE_NAME }}"
    customKeys:
      logicalDBName: configs
  type: postgresql
  versioningConfig:
    approach: new
```
