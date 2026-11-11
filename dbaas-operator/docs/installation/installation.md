# Installing DBaaS Operator

## Prerequisites

- Kubernetes 1.32 or newer. The CRDs use CEL validation rules, and the operator filters its cache by a CRD selectable
  field on `spec.operatorNamespace`. On an older server the operator's informers fail to sync at startup.
- dbaas-aggregator, installed in the same namespace. The operator always runs next to its aggregator: it is deployed
  into the aggregator's namespace and, with Basic authentication, mounts the aggregator's
  `dbaas-security-configuration-secret` from there. See [Authentication](parameters.md#authentication).
- The `monitoring.coreos.com` and `integreatly.org` CRDs, unless you set `MONITORING_ENABLED=false`.

## Install

```bash
helm upgrade --install <release> helm-templates/dbaas-operator \
  -f helm-templates/dbaas-operator/resource-profiles/<profile>.yaml \
  --set DBAAS_OPERATOR_ENABLED=true \
  --set NAMESPACE=<operator-namespace>
```

- `DBAAS_OPERATOR_ENABLED=true` is required. With the default `false` the chart renders only a placeholder ConfigMap.
- `NAMESPACE` must be set explicitly. The templates read `.Values.NAMESPACE` (default `default`), not
  `.Release.Namespace`, and build the `ClusterRoleBinding` subject from it. With `--namespace` alone, the binding
  points at a ServiceAccount in `default` and the operator reconciles nothing.
- A resource profile (`dev`, `dev-ha`, `prod-nonha` or `prod`) supplies `CPU_REQUEST` and `CPU_LIMIT`, which
  `values.yaml` doesn't set. Without it, rendering fails in `HorizontalPodAutoscaler.yaml` with
  `decimal division by 0`.

Then [grant Secret access](#secret-access) in every namespace whose resources reference or produce Secrets.

## RBAC

The chart creates:

| Resource                            | Name                         | Grants                                                                                                |
|-------------------------------------|------------------------------|-------------------------------------------------------------------------------------------------------|
| `ServiceAccount`                    | `dbaas-operator`             | Pod identity                                                                                          |
| `ClusterRole`, `ClusterRoleBinding` | `<NAMESPACE>-dbaas-operator` | DBaaS resources, their status and finalizers in all namespaces; Events outside the operator namespace |
| `Role`, `RoleBinding`               | `dbaas-operator`             | Leader-election leases and Events in the operator namespace                                           |

The `ClusterRole` grants no access to Secrets; see [Secret access](#secret-access). The authoritative rules are the
chart templates [`ClusterRole.yaml`](../../helm-templates/dbaas-operator/templates/ClusterRole.yaml) and
[`Role.yaml`](../../helm-templates/dbaas-operator/templates/Role.yaml). With `K8S_EVENTS_ENABLED=false` the chart omits
the Event rules.

### Restricted Environment

Where you can't create cluster-scoped objects with Helm, set `restrictedEnvironment: true`. The chart then skips the
`ClusterRole` and `ClusterRoleBinding`, and a cluster administrator applies them before the operator starts:

```bash
helm template <release> helm-templates/dbaas-operator \
  -f helm-templates/dbaas-operator/resource-profiles/dev.yaml \
  --set NAMESPACE=<operator-namespace> \
  --set DBAAS_OPERATOR_ENABLED=true --set restrictedEnvironment=false \
  -s templates/ClusterRole.yaml -s templates/ClusterRoleBinding.yaml \
  | kubectl apply -f -
```

The `NAMESPACE` and resource-profile arguments are required for the reasons given in [Install](#install). Without a
clone of the repository, use the script in
[Rendering the cluster RBAC without cloning the repository](../howto/render-cluster-rbac.md).

## Secret Access

The operator reads and writes Secrets only in namespaces that grant it access with a `Role` and a `RoleBinding`:

| Verbs on `secrets`                 | Needed by                                                                         |
|------------------------------------|-----------------------------------------------------------------------------------|
| `get`                              | `ExternalDatabase` and `PhysicalDatabase`, to read the credentials they reference |
| `get`, `create`, `update`, `patch` | `DatabaseSecretClaim`, to write and update its Secret                             |

The operator never lists, watches or deletes Secrets; a `DatabaseSecretClaim` Secret is deleted by garbage collection
with its owner. Without the grant, these resources fail with `forbidden`.

Apply [`config/samples/namespaced-secret-rbac.yaml`](../../config/samples/namespaced-secret-rbac.yaml) in each such
namespace. Its `RoleBinding` subject is the `dbaas-operator` ServiceAccount in the operator namespace. The operator
namespace itself needs the grant only if it hosts these resources.
