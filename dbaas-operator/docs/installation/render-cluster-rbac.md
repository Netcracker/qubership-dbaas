# Rendering the Cluster RBAC Without Cloning the Repository

## Prerequisites

- Bash, `curl`, `kubectl`, and Helm 3 on `PATH`.
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

   The script reads its parameters from environment variables:

   | Variable             | Default  | Controls                                                                           |
   |----------------------|----------|------------------------------------------------------------------------------------|
   | `NAMESPACE`          | `dbaas`  | Namespace of the ServiceAccount in the binding, and the prefix of the object names |
   | `K8S_EVENTS_ENABLED` | `true`   | Whether the `ClusterRole` grants `create` and `patch` on `events`                  |

   The script deletes `./dbaas-rbac-chart` and creates it again on every run, so don't keep other files in it.

   The script writes the manifest to `./dbaas-rbac-chart/out.yaml` and prints its kinds and names. For
   `NAMESPACE=dbaas` the output ends with:

   ```text
   ==> Done: ./dbaas-rbac-chart/out.yaml
   kind: ClusterRole
     name: 'dbaas-dbaas-operator'
   kind: ClusterRoleBinding
     name: 'dbaas-dbaas-operator'
     name: 'dbaas-dbaas-operator'
       namespace: 'dbaas'
   ```

   The second `name` under `ClusterRoleBinding` is its `roleRef`.

3. Review the manifest before applying:

   ```bash
   kubectl apply -f ./dbaas-rbac-chart/out.yaml
   ```

## Troubleshooting

| Message                                                          | Cause                                                | Action                                                         |
|------------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------|
| `helm not found in PATH`, `curl not found in PATH`               | The tool isn't installed or isn't on `PATH`          | Install the tool or add it to `PATH`                           |
| `curl: (22) The requested URL returned error: 404`               | The ref doesn't exist or doesn't contain the templates | Check `REF`; a tag needs the full form `refs/tags/<tag>`     |
| `curl: (6) Could not resolve host`                               | No access to GitHub                                  | Check the network or the proxy settings (`HTTPS_PROXY`)        |
| `ClusterRole or ClusterRoleBinding is missing from the output`   | The templates at this ref render under other conditions | Read `out.yaml` and the `if` condition in the downloaded templates |
| `function "..." not defined`                                     | The templates at this ref use `_helpers.tpl`         | Add `_helpers.tpl` to the download loop in the script          |
