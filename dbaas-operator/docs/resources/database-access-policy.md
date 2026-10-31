# DatabaseAccessPolicy

`DatabaseAccessPolicy` declares which database roles microservices get on the databases of one microservice.
dbaas-aggregator applies it when a service asks for credentials, for example through a
[`DatabaseSecretClaim`](database-secret-claim.md).

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseAccessPolicy
metadata:
  name: my-policy
  namespace: my-namespace
spec:
  operatorNamespace: dbaas-system
  microserviceName: my-service
  services:
    - name: reporting-service
      roles:
        - readonly
  policy:
    - type: postgresql
      defaultRole: readonly
      additionalRole:
        - admin
```

## Fields

| Field                      | Mutable | Description                                                                                                                    |
|----------------------------|:-------:|--------------------------------------------------------------------------------------------------------------------------------|
| `microserviceName`         |   No    | The microservice whose databases the policy covers                                                                             |
| `services[]`               |   Yes   | Other services and the roles they get. `name` must match the service's `app.kubernetes.io/name` label.                         |
| `policy[]`                 |   Yes   | Per database type: `defaultRole`, given when a request names no role, and `additionalRole`, the other roles a request may name |
| `disableGlobalPermissions` |   Yes   | `true` turns off dbaas-aggregator's global role grants for these databases                                                     |

Set at least one of `services`, `policy` and `disableGlobalPermissions`; the API server rejects a policy with none.

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason          | Condition    | Meaning                              |
|-----------------|--------------|--------------------------------------|
| `PolicyApplied` | `Ready=True` | dbaas-aggregator accepted the policy |

```bash
kubectl get dbdap -n my-namespace
```
