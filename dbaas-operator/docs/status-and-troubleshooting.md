# Status and Troubleshooting

## Status

Every resource reports its state the same way. Read `status.conditions` in automation and alerts; `status.phase` is a
summary for `kubectl get`.

### Conditions

| Condition | `True`                                                    | `False`                                                            |
|-----------|-----------------------------------------------------------|--------------------------------------------------------------------|
| `Ready`   | The current spec was applied                              | Applying it failed or is still waiting; see `reason` and `message` |
| `Stalled` | Permanent error: fix the spec, the operator doesn't retry | No permanent error; the normal value on success too                |

How to read them:

| `Ready` | `Stalled` | Meaning                                         |
|---------|-----------|-------------------------------------------------|
| `True`  | `False`   | Done                                            |
| `False` | `False`   | Transient error or a wait; the operator retries |
| `False` | `True`    | The spec must change                            |

`lastTransitionTime` changes only when the condition's status changes, not when its reason or message does.

### Phases

| Phase                  | Meaning                                                                         |
|------------------------|---------------------------------------------------------------------------------|
| *(empty)*              | Created; the operator hasn't written a status yet                               |
| `Processing`           | A reconcile is running                                                          |
| `Succeeded`            | The desired state was reached                                                   |
| `WaitingForDependency` | `InternalDatabase` and `PhysicalDatabase` only: the aggregator is still working |
| `BackingOff`           | Transient error; retried with [backoff](#reconcile-backoff)                     |
| `InvalidConfiguration` | Permanent error; matches `Stalled=True`                                         |

### Other Status Fields

- `status.observedGeneration`: the generation last fully handled. It lags `metadata.generation` while a change is in
  progress or after a transient failure.
- `status.lastRequestId`: the request ID of the last reconcile. Search for it in operator and dbaas-aggregator logs.
  `DatabaseSecretClaim` doesn't set it; its Events carry the request ID instead.

### Shared Reasons

Each resource page lists its own reasons; every kind can report these:

| Reason               | Condition      | Cause                                                                                          |
|----------------------|----------------|------------------------------------------------------------------------------------------------|
| `InvalidSpec`        | `Stalled=True` | The operator rejected the spec before calling the aggregator                                   |
| `AggregatorRejected` | `Stalled=True` | The aggregator answered `400`, `403`, `409`, `410` or `422`                                    |
| `Unauthorized`       | `Ready=False`  | The aggregator answered `401`; see [Authentication](installation/parameters.md#authentication) |
| `AggregatorError`    | `Ready=False`  | The aggregator answered `5xx` or another `4xx`, or wasn't reachable                            |

## Retries

### Reconcile Backoff

A reconcile that fails with a transient error, such as an aggregator `5xx`, a network error or a missing Secret, is
retried with exponential backoff per resource, and the phase is `BackingOff`. The delay starts at
`--backoff-base-delay`, doubles on each consecutive failure up to `--backoff-max-delay`, and gets up to 10% jitter. A
success resets it. See [Startup flags](installation/parameters.md#startup-flags).

### Exponential Poll Backoff

Waiting for work the aggregator hasn't finished isn't a failure, and uses a separate, shorter sequence: 5 s, 10 s,
20 s, 40 s, then every 60 s, with jitter. It applies to:

- an `InternalDatabase` waiting for asynchronous provisioning or a tenant database;
- a `PhysicalDatabase` waiting for a role migration;
- a `DatabaseSecretClaim` waiting for its database (`DatabaseNotFound`), its role (`EmptyConnectionProperties`), its
  composite structure (`NamespaceNotInComposite`) or its policy grant (`AccessPolicyNotReady`).

The sequence starts again at 5 s after a spec change, a new operation or an operator restart.

Two retries follow neither backoff: the resync intervals in [Settings](installation/parameters.md#settings), and the
hourly re-read of every `DatabaseSecretClaim`.

## Events

With `K8S_EVENTS_ENABLED=true`, the default, the operator records an Event for each outcome in the namespace of the
resource. See them with `kubectl describe` or
`kubectl get events -n <namespace> --field-selector involvedObject.name=<name>`.

| Type    | Reason                       | Emitted when                                                                     |
|---------|------------------------------|----------------------------------------------------------------------------------|
| Normal  | `DatabaseProvisioned`        | An `InternalDatabase` was provisioned                                            |
| Normal  | `ProvisioningStarted`        | An `InternalDatabase` started asynchronous provisioning                          |
| Normal  | `DatabaseRegistered`         | An `ExternalDatabase` was registered                                             |
| Normal  | `PhysicalDatabaseRegistered` | A `PhysicalDatabase` was registered                                              |
| Normal  | `PolicyApplied`              | A `DatabaseAccessPolicy` was applied                                             |
| Normal  | `BalancingRuleApplied`       | A balancing rule was applied                                                     |
| Normal  | `SecretCreated`              | A `DatabaseSecretClaim` created its Secret                                       |
| Normal  | `SecretRotated`              | A `DatabaseSecretClaim` wrote changed credentials                                |
| Warning | `InvalidSpec`                | The operator rejected the spec                                                   |
| Warning | `AggregatorRejected`         | The aggregator rejected the request, or an `InternalDatabase` operation `FAILED` |
| Warning | `AggregatorError`            | An aggregator call failed, including a balancing-rule cleanup                    |
| Warning | `Unauthorized`               | The aggregator, or a `PhysicalDatabase` adapter, answered `401`                  |
| Warning | `OperationTerminated`        | An `InternalDatabase` operation was terminated; the operator submits it again    |
| Warning | `SecretError`                | A referenced credentials Secret couldn't be read                                 |
| Warning | `SecretConflict`             | A `DatabaseSecretClaim` Secret belongs to someone else                           |
| Warning | `DatabaseNotFound`           | A `DatabaseSecretClaim` database doesn't exist yet                               |
| Warning | `DatabaseNotFoundTimeout`    | It still doesn't exist after 10 minutes                                          |
| Warning | `EmptyConnectionProperties`  | The aggregator returned no credentials for the role                              |
| Warning | `NamespaceNotInComposite`    | A cross-namespace claim and its database share no composite structure            |
| Warning | `CompositeLookupFailed`      | The operator could not read the composite structures                             |
| Warning | `AccessPolicyNotReady`       | A cross-namespace claim has no policy grant for its role yet                     |
| Warning | `AdapterError`               | A `PhysicalDatabase` adapter answered `404` or `503`                             |
| Warning | `RegistrationConflict`       | Another `PhysicalDatabase` claims the same adapter or identifier                 |

## Troubleshooting

Start with the conditions and the request ID:

```bash
kubectl get <kind> <name> -n <namespace> -o jsonpath='{.status.conditions}' | jq .
kubectl get <kind> <name> -n <namespace> -o jsonpath='{.status.lastRequestId}'
kubectl logs -n <operator-namespace> deploy/dbaas-operator | grep <request-id>
```

### The Operator Pod Doesn't Start

- `FailedMount` for `dbaas-security-configuration-secret`: the operator was installed outside the dbaas-aggregator
  namespace, or the aggregator chart hasn't created the Secret yet.
- The pod exits right after start: `users.json` has no `dbaas-operator` entry, or `CLOUD_NAMESPACE` is unset. Check
  the last log lines.
- Informers fail to sync: the cluster is older than Kubernetes 1.32; see
  [Prerequisites](installation/installation.md#prerequisites).

### A Resource Has No Status

- `spec.operatorNamespace` doesn't name this operator's namespace, so the operator ignores the resource. It stays
  without a status; see [Operator assignment](DBaaS%20Operator.md#operator-assignment).
- The `ClusterRoleBinding` points at the wrong ServiceAccount because `NAMESPACE` wasn't set; see
  [Install](installation/installation.md#install).

### A `DatabaseSecretClaim` Stays in `Processing`

The operator couldn't read or write the target Secret, usually because the namespace has no
[Secret access](installation/installation.md#secret-access) grant. No condition or Event is written; the operator
log shows the `forbidden` error.

### `Stalled=True`

Read the condition's `message`, fix the spec, and apply it again. For an immutable field, delete the resource and
create it again.

### `Unauthorized`

Check that `M2M_AUTH_MODE` matches dbaas-aggregator's setting, and that the operator's user or ServiceAccount has the
`CLUSTER_OPERATOR` and `DB_CLIENT` roles; see [Authentication](installation/parameters.md#authentication).

### `DatabaseNotFound` or `DatabaseNotFoundTimeout`

The `DatabaseSecretClaim` classifier matches no database. Either the database hasn't been provisioned yet (check the
`InternalDatabase`), or the classifier differs from the one the database was created with. A `scope: tenant` claim
needs the tenant database to exist; see [Tenant databases](resources/internal-database.md#tenant-databases).

### `NamespaceNotInComposite`, `CompositeLookupFailed` or `AccessPolicyNotReady`

A cross-namespace `DatabaseSecretClaim` is waiting, and no credentials are requested until the check passes. An
existing Secret is left in place. See
[Cross-namespace access](resources/database-secret-claim.md#cross-namespace-access).

- `NamespaceNotInComposite`: register both namespaces in one composite structure, or recreate the claim with the correct
  `classifier.namespace`.
- `CompositeLookupFailed`: the operator couldn't read the composite structures from dbaas-aggregator. The message has
  the cause; the claim retries with the reconcile backoff.
- `AccessPolicyNotReady`: the owner's `DatabaseAccessPolicy` doesn't grant the claim's service the requested role. Check
  the service name and role.

### A Balancing Rule Is Stuck in `Terminating`

Its finalizer can't remove the rules from dbaas-aggregator. The status keeps its last value; look at the Warning
`AggregatorError` Events and check that `spec.operatorNamespace` still names a running operator.
