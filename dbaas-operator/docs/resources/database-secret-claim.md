# DatabaseSecretClaim

`DatabaseSecretClaim` writes the credentials of a database that dbaas-aggregator manages into a Secret in its
namespace, and keeps the Secret current when the credentials change. The database must be provisioned separately, for
example by an [`InternalDatabase`](internal-database.md).

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseSecretClaim
metadata:
  name: my-app-db-secret
  namespace: my-namespace
  labels:
    app.kubernetes.io/name: my-service
spec:
  operatorNamespace: dbaas-system
  classifier:
    microserviceName: my-service
    scope: service
    namespace: my-namespace
  type: postgresql
  userRole: admin
  secretName: my-app-db-secret
```

## Fields

The classifier is described in [Classifier](../DBaaS%20Operator.md#classifier).

| Field                                       | Mutable  | Description                                                                                                                                                    |
|---------------------------------------------|:--------:|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `metadata.labels["app.kubernetes.io/name"]` |    —     | **Required.** The service asking for credentials; dbaas-aggregator uses it to resolve the role through the [`DatabaseAccessPolicy`](database-access-policy.md) |
| `classifier`                                |    No    | Identity of the database                                                                                                                                       |
| `type`                                      |    No    | Database type                                                                                                                                                  |
| `userRole`                                  | Once set | Role of the credentials. When empty, dbaas-aggregator picks the policy's `defaultRole`. It can be added or removed later, but not changed to another value.    |
| `secretName`                                |    No    | Secret to write. Two claims in one namespace can't share it.                                                                                                   |

The namespace needs [Secret access](../installation/installation.md#secret-access) with `get`, `create`, `update`
and `patch`.

## The Secret

The Secret is owned by the claim and deleted with it. It has the labels `app.kubernetes.io/managed-by=dbaas-operator`
and `app.kubernetes.io/name`, and two keys:

- `connectionProperties.json`: the connection properties from dbaas-aggregator, such as `url`, `host`, `port`,
  `username`, `password` and `role`. The exact set depends on the adapter.
- `metadata.json`: the `classifier`, `type` and `userRole` of the request, plus the database `id`, `name`, `namespace`
  and `settings` when known. dbaas-client uses it to match a mounted Secret to a database request.

```bash
kubectl get secret my-app-db-secret -n my-namespace \
  -o jsonpath='{.data.connectionProperties\.json}' | base64 -d | jq .
```

The operator writes the Secret only when its content changes, so pods that mount it aren't woken without a reason.

## Credential Rotation

When credentials change on the dbaas-aggregator side, for example after a rotation or a restore, the operator's
rotation poller sees the change in the aggregator's changed-databases feed within `DBAAS_ROTATION_POLL_INTERVAL`
(`30s` by default). It then re-reads every claim for that database, whatever its role, and each claim updates its
Secret if the content differs. Every claim is also re-read once an hour, which covers a change the poller missed.

`status.lastRotatedAt` records the last time the Secret content changed.

## Conflicts

A claim stalls with `SecretConflict` when its target Secret is owned by another resource, or when an older claim in
the namespace uses the same `secretName`. The older claim wins; the younger one recovers on its own once the older one
is deleted.

## Cross-Namespace Access

A claim can read a database owned by another namespace when both namespaces belong to the same composite structure.
Keep the database and its [`DatabaseAccessPolicy`](database-access-policy.md) in the owner namespace, and create the
claim in the consumer namespace with `classifier.namespace` set to the owner namespace. The Secret stays in the claim's
namespace.

```yaml
metadata:
  name: orders-db-reader
  namespace: reporting
  labels:
    app.kubernetes.io/name: reporting-service
spec:
  classifier:
    microserviceName: orders-service
    scope: service
    namespace: orders
```

The operator checks composite membership before it requests credentials, because dbaas-aggregator skips its own
namespace check for the operator's identity. dbaas-aggregator then applies the access policy. The label must name the
consuming service and differ from `classifier.microserviceName`, which would otherwise get the owner's access. Other
services the policy allows aren't distinguished: the label isn't a verified identity.

A claim applied before its composite or policy waits and recovers on its own. Composite membership is deployment-time
topology, so the check gates credential retrieval and doesn't revoke anything: a later denial blocks the claim and
leaves an existing Secret untouched.

## Reasons

Besides the [shared reasons](../status-and-troubleshooting.md#shared-reasons):

| Reason                      | Condition      | Meaning                                                                                                                           |
|-----------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `SecretCreated`             | `Ready=True`   | The Secret was created                                                                                                            |
| `SecretRotated`             | `Ready=True`   | The Secret got changed credentials                                                                                                |
| `SecretUpToDate`            | `Ready=True`   | The Secret was already current                                                                                                    |
| `SecretConflict`            | `Stalled=True` | See [Conflicts](#conflicts)                                                                                                       |
| `DatabaseNotFound`          | `Ready=False`  | No database matches the classifier yet; polled with the [poll backoff](../status-and-troubleshooting.md#exponential-poll-backoff) |
| `DatabaseNotFoundTimeout`   | `Ready=False`  | Still not found after 10 minutes; polling continues, Warning Events stop. Often a wrong classifier.                               |
| `EmptyConnectionProperties` | `Ready=False`  | The database has no credentials for the role yet; polled                                                                          |
| `NamespaceNotInComposite`   | `Ready=False`  | A cross-namespace claim and the database share no composite structure; polled                                                     |
| `CompositeLookupFailed`     | `Ready=False`  | The composite structures could not be read; retried with the reconcile backoff                                                    |
| `AccessPolicyNotReady`      | `Ready=False`  | A cross-namespace claim's service has no grant for the role yet; polled                                                           |

`InvalidSpec` means the `app.kubernetes.io/name` label is missing, or a cross-namespace claim's label equals
`classifier.microserviceName`. Problems with Secret access leave the claim in `Processing`; see
[Troubleshooting](../status-and-troubleshooting.md#a-databasesecretclaim-stays-in-processing).

This kind doesn't set `status.lastRequestId`. Its Events carry the request ID instead:

```bash
kubectl get events -n my-namespace --field-selector involvedObject.name=my-app-db-secret
```
