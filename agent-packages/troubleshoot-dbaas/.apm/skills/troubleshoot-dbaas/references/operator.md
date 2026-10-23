# DBaaS Operator troubleshooting

## dbaas-operator

### dbaas-operator cannot start because its Basic-auth Secret is missing or invalid

**Symptoms:**

- With `M2M_AUTH_MODE: legacy`, the pod of Deployment `dbaas-operator` has one of these states:
  - `ContainerCreating`, with event `FailedMount` for volume `dbaas-security-volume` because Secret
    `dbaas-security-configuration-secret` is not found.
  - `CrashLoopBackOff`, with the following log prefix and `user "dbaas-operator" not found in` in `<error>`:

    ```text
    failed to load aggregator credentials from /etc/dbaas/security — mount the dbaas-security-configuration-secret (Basic Auth mode): <error>
    ```

**Root cause:**

The standard DBaaS deployment creates `dbaas-security-configuration-secret` through the dbaas-aggregator chart before
the operator uses it. The event or log distinguishes two causes:

1. `FailedMount`: the Aggregator release did not create the Secret or the Secret was deleted.
2. `user "dbaas-operator" not found`: `users.json` came from an incompatible older Aggregator chart or was replaced
   outside the chart. The current chart always includes this user and preserves its generated password on upgrades.

**How to check:**

1. Match the exact event or log to the two causes above.
2. For `FailedMount`, use the supplied deployment evidence to find the preceding dbaas-aggregator render or release
   failure. Do not infer a namespace mismatch unless the evidence explicitly shows a non-standard per-service override.

**How to fix:**

1. Fix the dbaas-aggregator deployment failure and redeploy dbaas-aggregator so its chart creates a valid Secret. The
   operator pod starts automatically when the Secret appears.
2. Do not create the Secret or edit `users.json` by hand.

**Sources:**

- `dbaas-operator/cmd/credentials.go`
- `helm-templates/dbaas-aggregator/templates/Secret.yaml`
- `dbaas-operator/docs/DBaaS Operator.md` — section `Basic Auth (Default)`

### DBaaS custom resource has no status at all

**Symptoms:**

- `InternalDatabase`, `ExternalDatabase`, `DatabaseAccessPolicy`, `DatabaseSecretClaim`, or a balancing rule CR shows an
  empty `PHASE`, no conditions, and no events.
- Metric `dbaas_resource_unassigned{kind="<kind>",name="<name>"}` is `1`.

**Root cause:**

The operator reconciles a CR only when `spec.operatorNamespace` equals the operator's own namespace
(`CLOUD_NAMESPACE`). A different value — usually a typo or the workload namespace — makes the CR invisible to the
operator. Clusters upgraded to 6.15.0 without the `NamespaceBinding` migration have the same symptom.

**How to check:**

1. Compare `spec.operatorNamespace` of the CR with the namespace where Deployment `dbaas-operator` runs.

**How to fix:**

1. `spec.operatorNamespace` is immutable. Delete the CR and create it again with the exact namespace of the
   dbaas-operator instance that must manage it; this value must equal that operator's `CLOUD_NAMESPACE`. Supply the
   value explicitly. Do not use the workload namespace, and do not derive it from an aggregator URL unless the
   deployment platform's contract explicitly guarantees that URL host segment is the operator namespace.
2. For an unassigned `MicroserviceBalancingRule`, `NamespaceBalancingRule`, or `PermanentBalancingRule`, inspect
   `metadata.finalizers` in the supplied manifest before deletion. With no DBaaS finalizer, the CR has not been applied
   by this operator and may be recreated. With a DBaaS finalizer, deletion hangs because assignment is checked before
   cleanup; restore an operator whose `CLOUD_NAMESPACE` matches `spec.operatorNamespace`, then see
   `Balancing rule CR stuck in Terminating`. The other four kinds have no finalizer and can be deleted and recreated.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `High-Level Architecture`, `Custom Resources`
- `dbaas-operator/docs/howto/migrate-from-namespacebinding.md` — sections `Setting the operator namespace`,
  `During the gap`
- `dbaas-operator/docs/monitoring/DBaaS Operator Metrics.md` — `dbaas_resource_unassigned`

<!-- markdownlint-disable line-length -->

- `dbaas-operator/agent-packages/migrate-core-operator-dbaas-declarations/.apm/skills/migrate-core-operator-dbaas-declarations/references/mapping.md` — operator assignment

<!-- markdownlint-enable line-length -->

### DBaaS custom resource is BackingOff with reason Unauthorized

**Symptoms:**

- CR `status.phase: BackingOff`, condition `Ready=False`, reason `Unauthorized`, `Stalled=False`.
- Warning event `Unauthorized` on the CR.

**Root cause:**

dbaas-aggregator returned `401` to the operator:

1. Basic auth: the `dbaas-operator` password in `users.json` of Secret `dbaas-security-configuration-secret` changed,
   but dbaas-aggregator was not restarted. The operator reloads the mounted file; dbaas-aggregator reads it only at
   startup and therefore continues to expect the previous password.
2. M2M: the exact Aggregator authentication log identifies a rejected token audience or ServiceAccount role. The
   standard deployment supplies one M2M mode to both components and the Aggregator chart supplies the
   `dbaas-operator` role mapping; do not infer configuration drift without deployment evidence of an override.

**How to check:**

1. Read `M2M_AUTH_MODE` from the supplied deployment parameters: `legacy` selects Basic authentication; `hybrid` or
   `k8s` selects M2M Bearer-token authentication.
2. For Basic auth, compare the first `401` with changes to `dbaas-security-configuration-secret` and the most recent
   dbaas-aggregator restart.
3. For M2M, use the exact Aggregator authentication error. Check mode, audience, or roles only when that error or the
   supplied manifests identify the rejected property.

**How to fix:**

1. For Basic auth, restart dbaas-aggregator after Secret `dbaas-security-configuration-secret` changes. The operator
   reloads the file by itself.
2. For M2M, correct only the property named by the Aggregator authentication error. If the evidence shows a
   per-service M2M override, remove that override and redeploy through the standard deployment path.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `Authentication: Basic Auth or M2M Token`, `Common Status Model`
- `dbaas/dbaas-aggregator/src/main/java/com/netcracker/cloud/dbaas/security/DbaasUsersLoader.java`

### DBaaS custom resource is BackingOff with reason AggregatorError

**Symptoms:**

- CR `status.phase: BackingOff`, condition `Ready=False`, reason `AggregatorError`, `Stalled=False`.
- Warning event `AggregatorError` on the CR.

**Root cause:**

dbaas-aggregator returned `5xx`, an unclassified `4xx` (for example `404`), or did not answer within 30 s. The operator
retries with exponential backoff from 1 s up to 5 min. The CR heals by itself when dbaas-aggregator recovers.

**How to check:**

1. Take `status.lastRequestId` from the CR (for `DatabaseSecretClaim`: the `requestId=` in the event) and find the
   same `request_id=` in the dbaas-aggregator log. The aggregator error there is the real cause; route its
   `CORE-DBAAS-` code or message through `log-signatures.md` and `aggregator.md`.
2. If dbaas-aggregator has no line with that request ID, it was unreachable: check `DBAAS_AGGREGATOR_URL` (default
   `http://dbaas-aggregator:8080`) and whether the aggregator pod was down at that time.

**How to fix:**

1. Fix the aggregator-side cause found in step 1. No change to the CR is needed.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `Common Response Handling`, `Reconcile Backoff`

### DBaaS custom resource is InvalidConfiguration

**Symptoms:**

- CR `status.phase: InvalidConfiguration`, condition `Ready=False`, `Stalled=True`, reason `InvalidSpec` or
  `AggregatorRejected`.
- Warning event `InvalidSpec` or `AggregatorRejected`.

**Root cause:**

The operator has stopped retrying until the resource generation changes. The reason determines whether the initiating
failure was actually a specification error:

1. `InvalidSpec` — rejected by the operator before calling dbaas-aggregator. Common reasons: `classifier.namespace`
   differs from the CR's `metadata.namespace`; a reserved key (`microserviceName`, `scope`, `namespace`, `tenantId`,
   `customKeys`) in `classifier.extraKeys`; a duplicate `name` in `credentialsSecretRef.keys` (`ExternalDatabase`);
   missing label `app.kubernetes.io/name` (`DatabaseSecretClaim`); `clone` without `sourceClassifier`.
2. `AggregatorRejected` — dbaas-aggregator returned `400`, `403`, `409`, `410`, or `422`, or an `InternalDatabase`
   async operation ended `FAILED`. This is the operator's response classification, not proof that the CR spec caused
   the failure. The condition message and correlated Aggregator or adapter log contain the initiating error.

**How to check:**

1. Read the condition `reason` and `message`.
2. For `InvalidSpec`, use the named field and validation message; no Aggregator call was made.
3. For `AggregatorRejected`, correlate `status.lastRequestId` or `status.trackingId` with dbaas-aggregator logs. Route
   the initiating `CORE-DBAAS-` code or message through `log-signatures.md` and `aggregator.md`. Do not recommend a spec
   change when the evidence instead shows a transient Aggregator, adapter, or database-cluster failure.

**How to fix:**

1. For `InvalidSpec`, correct the field named in the condition message. The immutable identity fields are:
   - every kind: `spec.operatorNamespace`
   - `InternalDatabase`: `spec.classifier`, `spec.type`
   - `ExternalDatabase`: `spec.classifier`, `spec.type`, `spec.dbName`
   - `DatabaseAccessPolicy`: `spec.microserviceName`
   - `DatabaseSecretClaim`: `spec.classifier`, `spec.type`, `spec.secretName`; `spec.userRole` cannot change directly
     from one set value to another
2. For `AggregatorRejected`, fix the initiating cause found in the correlated logs. If it is a genuine request or
   policy error, apply the corresponding Aggregator case. If it is a runtime or product failure, do not invent a CR
   change; record that the operator will not retry this generation and escalate the safe retry procedure to the DBaaS
   team after the runtime cause is repaired.
3. For `InternalDatabase`, `ExternalDatabase`, and `DatabaseAccessPolicy`, delete and recreate the CR to change an
   immutable identity field.
4. For `DatabaseSecretClaim`, first create a replacement claim with a different CR name and `spec.secretName`, wait for
   its Secret to be ready, and move every workload to that Secret.
5. **DANGEROUS — deleting a `DatabaseSecretClaim` also deletes its owned Secret and breaks any workload still using
   it.** Delete the old claim only after no workload references its Secret.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `Common Status Model`, `ExternalDatabase Status Reference`,
  `InternalDatabase Status Reference`, `DatabaseAccessPolicy`, `DatabaseSecretClaim`
- `dbaas-operator/docs/howto/migrate-declarations-from-core-operator.md` — section `Gotchas`

### ExternalDatabase is BackingOff with reason SecretError

**Symptoms:**

- `ExternalDatabase` `status.phase: BackingOff`, reason `SecretError`; Warning event `SecretError`.
- Metric `dbaas_secret_resolution_errors_total` grows with `reason` one of `secret_not_found`, `key_missing`,
  `key_empty`, `forbidden`, `secret_read_failed` (label `exported_namespace` when scraped).

**Root cause:**

The credential Secret referenced in `credentialsSecretRef` cannot be read:

1. `forbidden` — the namespace has no Role/RoleBinding granting the operator's ServiceAccount `get`, `create`,
   `update`, `patch` on `secrets`. The operator has no cluster-wide Secret permission.
2. `secret_not_found`, `key_missing`, `key_empty` — the Secret or key named in the CR does not exist or is empty.

**How to check:**

1. Read the `reason` label of `dbaas_secret_resolution_errors_total`, or the event message.

**How to fix:**

1. `forbidden`: add the Role and RoleBinding `dbaas-operator-secrets` in the CR's namespace (shipped by the dbaas-agent
   chart wherever Cloud Core is installed; template: `dbaas-operator/config/samples/namespaced-secret-rbac.yaml`).
2. Other reasons: create or fill the Secret/key. The operator picks it up on the next resync,
   `DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL` (default `10m`).

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `ExternalDatabase Status Reference`, `Secret Access (Namespaced)`

### DatabaseSecretClaim stuck in Processing without conditions

**Symptoms:**

- `DatabaseSecretClaim` `status.phase: Processing` for a long time, with no `Ready` condition and no events.
- The target Secret is not created; the application pod waits in `ContainerCreating` with `FailedMount` for it.

**Root cause:**

A Kubernetes API error while reading or writing the target Secret returns before any condition is written. The usual
cause is missing Secret RBAC (`forbidden`) in the CR's namespace.

**How to check:**

1. Look in the dbaas-operator log for `forbidden` on `secrets` in the CR's namespace.

**How to fix:**

1. Add the Role and RoleBinding `dbaas-operator-secrets` in that namespace — see
   `ExternalDatabase is BackingOff with reason SecretError`.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — section `DatabaseSecretClaim Status Reference`
- `dbaas-operator/docs/howto/onboarding.md` — section `Secret permissions in the workload namespace`

### DatabaseSecretClaim reports DatabaseNotFound or DatabaseNotFoundTimeout

**Symptoms:**

- `DatabaseSecretClaim` `status.phase: BackingOff`, reason `DatabaseNotFound` or `DatabaseNotFoundTimeout`.
- Warning event `DatabaseNotFound` repeating, then one `DatabaseNotFoundTimeout` after about 10 minutes.
- `status.firstNotFoundAt` is set.

**Root cause:**

dbaas-aggregator returned `404` / `CORE-DBAAS-4006` for the claim's classifier:

1. The database is not provisioned yet — its `InternalDatabase` is still `WaitingForDependency` or failed.
2. The claim's classifier differs from the database's classifier (typo, missing `customKeys`/`extraKeys`, wrong
   `tenantId`). `DatabaseNotFoundTimeout` points to this cause.

**How to check:**

1. Find the `InternalDatabase` with the same classifier and type and read its phase.
2. Compare the two classifiers field by field.

**How to fix:**

1. Cause 1: fix the `InternalDatabase` first; the claim resolves by itself (polling continues).
2. Cause 2: `spec.classifier` and `spec.secretName` are immutable. Create a replacement claim with a different CR name
   and `spec.secretName`, wait until its Secret is ready, and move every workload to the replacement Secret. Delete the
   old claim only after no workload references its Secret.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — section `DatabaseSecretClaim Status Reference`

### DatabaseSecretClaim reports SecretConflict

**Symptoms:**

- `DatabaseSecretClaim` `status.phase: InvalidConfiguration`, reason `SecretConflict`, `Stalled=True`.

**Root cause:**

1. The target Secret `secretName` already exists and is owned by another resource.
2. Another `DatabaseSecretClaim` in the namespace claims the same `secretName`; the younger claim loses.

**How to check:**

1. Read the condition message; it names the conflicting owner or claim.

**How to fix:**

1. `spec.secretName` is immutable. Create a replacement claim with a different CR name and unique `spec.secretName`,
   wait until its Secret is ready, and move every workload to it; or remove the conflicting owner only when it is
   confirmed obsolete.
2. **DANGEROUS — deleting the existing Secret breaks every workload that consumes it until a valid replacement is
   written.** Delete it only after its owner is identified, every consuming workload is known, and the owner confirms
   that the Secret is obsolete.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — section `DatabaseSecretClaim Status Reference`

### InternalDatabase stays in WaitingForDependency

**Symptoms:**

- `InternalDatabase` `status.phase: WaitingForDependency`, reason `ProvisioningStarted`, for longer than the database
  creation normally takes.

**Root cause:**

Provisioning is asynchronous and not failed. Two variants:

1. `status.trackingId` set — the declarative operation is still `IN_PROGRESS` in dbaas-aggregator. The adapter is slow
   or blocked (for example, adapter health `PROBLEM`, or `clone` of a large source database).
2. `status.trackingId` empty — `scope: tenant` with a pinned `tenantId`: the get-or-create of the tenant database
   returns `202` while the database is still being created.

**How to check:**

1. Read `status.trackingId`.
2. Find the tracking ID or `status.lastRequestId` in the dbaas-aggregator log and check for adapter errors; check
   `Physical database adapter reports a problem`.

**How to fix:**

1. Fix the adapter or database cluster problem; the operator re-polls (5 s growing to 60 s) and completes by itself.
   Do not delete and recreate the CR to "restart" it while an operation is in flight.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `InternalDatabase Status Reference`, `Exponential Poll Backoff`

### Balancing rule CR stuck in Terminating

**Symptoms:**

- `MicroserviceBalancingRule`, `NamespaceBalancingRule`, or `PermanentBalancingRule` stays in `Terminating`.
- Its status still shows the pre-deletion phase; Warning event `AggregatorError` repeats.

**Root cause:**

1. The aggregator cleanup call fails (see `DBaaS custom resource is BackingOff with reason AggregatorError`); no status
   is written during deletion.
2. `spec.operatorNamespace` does not match any operator, so no operator removes the finalizer — see
   `DBaaS custom resource has no status at all`.

**How to check:**

1. Read the CR's Warning events and compare `spec.operatorNamespace` with the operator namespace.

**How to fix:**

1. Fix the aggregator-side error; deletion completes on the next retry.
2. **DANGEROUS — leaves the rule applied in dbaas-aggregator with no CR managing it.** Removing the finalizer by hand
   is a last resort; afterward delete the rule through the balancing rules REST API.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — section `Balancing Rule Status Reference`

### All DBaaS custom resources disappeared after an operator redeploy

**Symptoms:**

- After a redeploy of `dbaas-operator`, the CRDs `internaldatabases.dbaas.netcracker.com` and the others are gone, and
  all DBaaS CRs in all namespaces are deleted.

**Root cause:**

The CRDs ship as templates of the `dbaas-operator` chart, gated by `DBAAS_OPERATOR_ENABLED`. Changing an existing Helm
release from `true` to `false` removes the previously managed CRDs and, with them, every CR. Databases already
registered in dbaas-aggregator are not deleted.

**How to check:**

1. Check `DBAAS_OPERATOR_ENABLED` in the deployment parameters used for the last `dbaas-operator` deployment.

**How to fix:**

1. Redeploy `dbaas-operator` with `DBAAS_OPERATOR_ENABLED: true`, then redeploy the services whose charts contain the
   CRs so they are recreated. Registered databases are found again by classifier.

**How to avoid this issue:**

To pause the operator, set `REPLICAS: 0` in the `dbaas-operator` deployment parameters. Never use
`DBAAS_OPERATOR_ENABLED: false` for this.

**Sources:**

- `dbaas-operator/docs/DBaaS Operator.md` — sections `Prerequisites and Installation` (`Upgrade`),
  `Configuration Parameters`
