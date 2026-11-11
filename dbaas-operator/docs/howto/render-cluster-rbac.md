# Rendering the Cluster RBAC Without Cloning the Repository

With `restrictedEnvironment: true`, the dbaas-operator chart skips the `ClusterRole` and `ClusterRoleBinding`, and a
cluster administrator applies them before the operator starts; see
[Restricted Environment](../installation/installation.md#restricted-environment). The
[`render-dbaas-rbac.sh`](render-dbaas-rbac.sh) script renders the two objects without a clone of the repository: it
downloads `ClusterRole.yaml` and `ClusterRoleBinding.yaml` from GitHub into a minimal chart and runs `helm template` on
it.

## Prerequisites

- Bash, `curl`, and Helm 3 on `PATH`.
- Access to `raw.githubusercontent.com`.

## Render the Objects

1. Download [`render-dbaas-rbac.sh`](render-dbaas-rbac.sh) and make it executable:

   ```bash
   curl -fsSLO https://raw.githubusercontent.com/Netcracker/qubership-dbaas/refs/heads/main/dbaas-operator/docs/howto/render-dbaas-rbac.sh
   chmod +x render-dbaas-rbac.sh
   ```

2. Run it with the namespace the operator is deployed to:

   ```bash
   NAMESPACE=<operator-namespace> ./render-dbaas-rbac.sh
   ```

   The script writes the manifest to `/tmp/dbaas-rbac-chart/out.yaml` and prints its kinds and names. For
   `NAMESPACE=dbaas` the output ends with:

   ```text
   ==> Done: /tmp/dbaas-rbac-chart/out.yaml
   kind: ClusterRole
     name: 'dbaas-dbaas-operator'
   kind: ClusterRoleBinding
     name: 'dbaas-dbaas-operator'
     name: 'dbaas-dbaas-operator'
       namespace: 'dbaas'
   ```

   The second `name` under `ClusterRoleBinding` is its `roleRef`.

3. Apply the manifest:

   ```bash
   kubectl apply -f /tmp/dbaas-rbac-chart/out.yaml
   ```

Set `NAMESPACE` to the operator namespace. The `ClusterRoleBinding` subject is the `dbaas-operator` ServiceAccount in
that namespace, and with a wrong value the operator reconciles nothing.

## Parameters

The script reads its parameters from environment variables:

| Variable             | Default                 | Controls                                                                         |
|----------------------|-------------------------|----------------------------------------------------------------------------------|
| `NAMESPACE`          | `dbaas`                 | Namespace of the ServiceAccount in the binding, and the prefix of the object names |
| `SERVICE_NAME`       | `dbaas-operator`        | Name of the ServiceAccount, and the suffix of the object names                   |
| `APPLICATION_NAME`   | `dbaas`                 | The `app.kubernetes.io/part-of` label                                            |
| `MANAGED_BY`         | `helm`                  | The `app.kubernetes.io/managed-by` label                                         |
| `K8S_EVENTS_ENABLED` | `true`                  | Whether the `ClusterRole` grants `create` and `patch` on `events`                |
| `REF`                | `main`                  | Branch to download the templates from, or a full ref such as `refs/tags/<tag>`   |
| `WORKDIR`            | `/tmp/dbaas-rbac-chart` | Directory for the temporary chart and `out.yaml`                                 |

Both objects are named `<NAMESPACE>-<SERVICE_NAME>`, cut to 63 characters. Keep `SERVICE_NAME` and
`K8S_EVENTS_ENABLED` equal to the values the operator chart is installed with.

The script deletes `WORKDIR` and creates it again on every run, so don't point it at a directory that holds other
files.

## Troubleshooting

| Message                                                          | Cause                                                | Action                                                         |
|------------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------|
| `helm not found in PATH`, `curl not found in PATH`               | The tool isn't installed or isn't on `PATH`          | Install the tool or add it to `PATH`                           |
| `curl: (22) The requested URL returned error: 404`               | The ref doesn't exist or doesn't contain the templates | Check `REF`; a tag needs the full form `refs/tags/<tag>`     |
| `curl: (6) Could not resolve host`                               | No access to GitHub                                  | Check the network or the proxy settings (`HTTPS_PROXY`)        |
| `ClusterRole or ClusterRoleBinding is missing from the output`   | The templates at this ref render under other conditions | Read `out.yaml` and the `if` condition in the downloaded templates |
| `function "..." not defined`                                     | The templates at this ref use `_helpers.tpl`         | Add `_helpers.tpl` to the download loop in the script          |
