# Onboarding a Service to DBaaS Operator

DBaaS Operator moves database access out of a service's runtime and into resources the service ships
with its Helm chart. Where a service previously called dbaas-aggregator on startup, it now declares an
`InternalDatabase` for the database and a `DatabaseSecretClaim` for its credentials, and the operator
reconciles both.

The complete custom resource reference, status model, and configuration parameters are documented in
[DBaaS Operator](../DBaaS%20Operator.md).

---

## Migration paths

| Current state of the service | Recommended path |
|---|---|
| Provisions databases through dbaas-aggregator at runtime | [Automated migration](#automated-migration) with `dbaas-mounted-secret-migration` |
| Ships Core Operator `kind: DBaaS` declarations | [Automated migration](#automated-migration) with `migrate-core-operator-dbaas-declarations`, or the [declaration mapping guide](migrate-declarations-from-core-operator.md) |
| Has no existing DBaaS integration | [Manual migration](#manual-migration) |

A cluster that still runs `NamespaceBinding` objects requires
[the NamespaceBinding migration](migrate-from-namespacebinding.md) before it moves to 6.15.0. That
is a cluster-level prerequisite, not per-service work.

---

## Automated migration

Two agent skills perform the mechanical conversion. Both are APM packages that run against a service
repository and produce a reviewable diff; neither applies anything to a cluster.

### Runtime provisioning to mounted Secrets

`dbaas-mounted-secret-migration` inventories the logical databases a service reaches for, then
generates one `InternalDatabase` per `(classifier, type)` and one `DatabaseSecretClaim` per
`(classifier, type, role)`.

```sh
apm install Netcracker/qubership-dbaas/dbaas-operator/agent-packages/dbaas-mounted-secret-migration
```

Two constraints apply. The skill supports Go, Spring, and Quarkus, and reports other frameworks as
out of scope. It also does not produce a complete cutover: identities whose tenant is derived from
the request context remain on the runtime path, and the REST fallback is retained deliberately.

### Core Operator declarations to custom resources

`migrate-core-operator-dbaas-declarations` converts `DatabaseDeclaration` to `InternalDatabase` and
`DbPolicy` to `DatabaseAccessPolicy`, locating them anywhere in the repository.

```sh
apm install Netcracker/qubership-dbaas/dbaas-operator/agent-packages/migrate-core-operator-dbaas-declarations
```

Either skill is invoked by name from the service repository. The generated resources should be
compared against the source manifests before they are applied.

---

## Manual migration

The equivalent work performed by hand, and the starting point for a service with no existing integration.

1. **Identify every database the service uses.** Calls to the dbaas-aggregator `/api/v3/dbaas/*` API
   in application code and in any DBaaS client wrapper, together with legacy `DatabaseDeclaration`
   and `DbPolicy` manifests in the chart, define the set. Each distinct
   `(classifier, database type)` pair corresponds to one database.

2. **Select a custom resource for each requirement.**

   | Requirement | Custom resource |
   |------|----|
   | Provision a new logical database | `InternalDatabase` |
   | Register a database that already exists elsewhere | `ExternalDatabase` |
   | Expose credentials as a mountable Secret (see [Obtaining credentials](#obtaining-credentials)) | `DatabaseSecretClaim` |
   | Grant a microservice a role on a database | `DatabaseAccessPolicy` |

   The balancing rule resources (`MicroserviceBalancingRule`, `NamespaceBalancingRule`,
   `PermanentBalancingRule`) control physical database placement rather than a service's own database.
   Each is a singleton with a fixed name in its scope, so two services in a namespace shipping one will
   contend for the same object.

3. **Set `spec.operatorNamespace` on every resource.** The value is the namespace the dbaas-operator
   instance that should reconcile the resource runs in. The field is required and immutable, and the
   ways a chart can supply it are described in
   [Setting the operator namespace](migrate-from-namespacebinding.md#setting-the-operator-namespace).

4. **Ship the resources in the service chart** and mount the Secret produced by `DatabaseSecretClaim`
   instead of provisioning at startup. The
   [`go-test-app-service` chart](../../../test-apps/go-test-app-service/helm-templates/go-test-app-service)
   demonstrates both.

---

## Obtaining credentials

`InternalDatabase` and `ExternalDatabase` concern only the existence of a database: the first asks
dbaas-aggregator to provision a new logical database, the second registers one that already exists.
Neither delivers credentials or produces a Kubernetes Secret.

Credentials are requested separately, through `DatabaseSecretClaim`, which resolves an
already-registered database by classifier and writes its connection properties into a Secret in the
workload namespace:

```yaml
apiVersion: dbaas.netcracker.com/v1
kind: DatabaseSecretClaim
metadata:
  name: orders-db-admin
  namespace: <workload-namespace>
spec:
  operatorNamespace: <operator-namespace>
  classifier:
    microserviceName: orders
    namespace: <workload-namespace>
    scope: service
  type: postgresql
  userRole: admin
  secretName: orders-db-admin-secret
```

The `classifier` and `type` must identify the same database the `InternalDatabase` or
`ExternalDatabase` describes, and `userRole` selects which role's credentials are written — one claim
per role. The Secret holds `connectionProperties.json` with the adapter-specific connection details,
and `metadata.json` describing the database, so a client can match the Secret to a request without
calling the aggregator.

Ordering does not have to be managed: a claim whose database does not exist yet receives
`DatabaseNotFound` and keeps polling, so both resources can ship in the same chart. After ten minutes
the `Ready` reason changes to `DatabaseNotFoundTimeout` and one Warning event is emitted, but polling
continues and the claim still recovers on its own. When credentials rotate, the operator rewrites the
Secret in place under the same name.

### Mounting credentials in the application container

The operator creates the Kubernetes Secret, but it does not modify the application's workload. The
service chart must expose that Secret as a volume in every application container that uses the
database.

Mounted-secret-capable DBaaS clients look in one fixed absolute path **inside the application
container**:

```text
/etc/secrets/dbaas-secrets
```

Each Secret must appear in its own immediate child directory. Mount the complete Secret read-only;
Kubernetes projects the two data keys written by the operator as files:

```text
/etc/secrets/dbaas-secrets/<secret-name>/metadata.json
/etc/secrets/dbaas-secrets/<secret-name>/connectionProperties.json
```

Set `<secret-name>` to the exact value of `DatabaseSecretClaim.spec.secretName`. For the claim above,
the complete mount path is `/etc/secrets/dbaas-secrets/orders-db-admin-secret`.

`spec.secretName` has no mandatory business naming pattern: it must be a valid Kubernetes Secret name
and must be unique among `DatabaseSecretClaim` resources in the namespace.

The Pod volume name is separate from the Secret name. It can be any valid, unique volume name, but the
same value must be used by `volumes[].name` and `volumeMounts[].name`:

```yaml
volumes:
  - name: orders-db-secret
    secret:
      secretName: orders-db-admin-secret
containers:
  - name: orders
    volumeMounts:
      - name: orders-db-secret
        mountPath: /etc/secrets/dbaas-secrets/orders-db-admin-secret
        readOnly: true
```

For multiple claims, add one volume and one immediate child mount per Secret to each container that
uses it. Do not mount the Secret's files directly at `/etc/secrets/dbaas-secrets`, add another level of
nesting, or use a different base path: the client scans only the immediate child directories. Do not
use `subPath` either, because Kubernetes does not propagate Secret updates to a `subPath` mount.

### Secret permissions in the workload namespace

The operator holds no cluster-wide Secret permission, so each namespace with a `DatabaseSecretClaim` —
or an `ExternalDatabase` that references a credential Secret — has to grant the operator's ServiceAccount
`get`, `create`, `update` and `patch` on `secrets`, through a Role and RoleBinding in that namespace.
Without them both fail with `forbidden`.

The dbaas-agent chart ships that pair, named `dbaas-operator-secrets`, wherever Cloud Core is installed.
A namespace without Cloud Core has to supply it, applied manually or from the service's own chart.
[`config/samples/namespaced-secret-rbac.yaml`](../../config/samples/namespaced-secret-rbac.yaml) is a
ready-to-apply bundle for one namespace; see
[Secret Access (Namespaced)](../DBaaS%20Operator.md#secret-access-namespaced) for the rationale.

### Starting before the Secret exists

Provisioning is asynchronous, so on a first deployment the pod may be scheduled before the Secret
exists, and an ordinary reference blocks it in `ContainerCreating` with a `FailedMount` event. Where
the database is not required for startup, declare the reference optional:

```yaml
volumes:
  - name: orders-db-secret
    secret:
      secretName: orders-db-admin-secret
      # The pod starts even when the Secret does not exist yet. The volume is empty
      # until the operator writes it, and the kubelet then populates it in place.
      optional: true
containers:
  - name: orders
    volumeMounts:
      - name: orders-db-secret
        mountPath: /etc/secrets/dbaas-secrets/orders-db-admin-secret
        readOnly: true
```

Use a volume, not an environment variable. `envFrom.secretRef.optional` and
`env.valueFrom.secretKeyRef.optional` also let the pod start, but environment variables are resolved
once at container start, so neither the first write nor a later rotation reaches a running container.
A mounted volume is refreshed by the kubelet and covers both. Keep the
[mounting layout described above](#mounting-credentials-in-the-application-container) so the client
can discover it.

`optional` governs pod startup only. The application still has to tolerate an absent file at startup
and read it once it appears, rather than resolving its datasource during initialization.

---

## Verification

The reconciliation outcome is reported in `status.conditions`. The `PHASE` column is a summary for
`kubectl get` and carries no information the conditions do not.

```bash
kubectl get dbidb,dbdsc,dbdap -n <workload-namespace>
kubectl describe internaldatabase <name> -n <workload-namespace>
```

`Ready=True` indicates that the current generation was processed successfully. `Ready=False` together
with `Stalled=True` indicates a permanent specification problem that is not retried until the spec
changes; the condition `Reason` and `Message` identify it. A resource that reports no status at all is
normally assigned to the wrong operator: check `spec.operatorNamespace`.

---

## Related documentation

- [DBaaS Operator](../DBaaS%20Operator.md) — custom resource reference, status and condition vocabulary,
  RBAC, authentication, and configuration parameters.
- [Migrating declarations from Core Operator](migrate-declarations-from-core-operator.md) — the
  field-by-field mapping that underlies the declaration skill.
- [Migrating from the retired NamespaceBinding model](migrate-from-namespacebinding.md) — the
  cluster-level change required before 6.15.0.
