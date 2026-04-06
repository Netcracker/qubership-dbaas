# dbaas-operator
// TODO(user): Add simple overview of use/purpose

## Description

`dbaas-operator` is a Kubernetes operator that reconciles one public custom resource kind in the first release:

| Kind | Purpose |
|---|---|
| `ExternalDatabase` | Registers a pre-existing (externally managed) database in dbaas-aggregator so microservices can discover its connection details. |

---

Alpha resources (`DatabaseDeclaration`, `DbPolicy`) remain under active development in the repository, but are not installed by `make install` / `make deploy`.

## ExternalDatabase

### Minimal example (no Secret)

```yaml
apiVersion: dbaas.netcracker.com/v1alpha1
kind: ExternalDatabase
metadata:
  name: my-postgres
  namespace: my-ns
spec:
  type: postgresql
  dbName: mydb
  classifier:
    namespace: my-ns
    microserviceName: my-service
    scope: service
  connectionProperties:
    - role: admin
      extraProperties:
        host: pg.example.com
        port: "5432"
        url: "jdbc:postgresql://pg.example.com:5432/mydb"
```

### With credentials from a Kubernetes Secret

Use `credentialsSecretRef` to read credentials from a Secret at reconcile time.
The Secret must exist in the same namespace as the CR.

```yaml
spec:
  connectionProperties:
    - role: admin
      credentialsSecretRef:
        name: pg-credentials       # Secret name
        keys:
          - key: db-user           # Secret.data key
            name: username         # aggregator request flat-map key
          - key: db-pass
            name: password
      extraProperties:
        host: pg.example.com
        port: "5432"
```

**Merge priority** (later sources win on key collision):
1. `extraProperties` — lowest priority
2. `role` — overrides `extraProperties["role"]`
3. `credentialsSecretRef.keys` — highest priority; Secret values override matching `extraProperties` keys

**Constraints enforced by the CRD:**
- `keys` is required when `credentialsSecretRef` is specified; at least one mapping must be present (`MinItems=1`).
- `keys[*].name` values must be unique within the list (CEL validation).

**Transient vs permanent errors:**
- Secret not found, key missing, or key value is empty → `BackingOff` (retried automatically; no operator restart needed after fixing the Secret).
- Aggregator rejects the request (400/403/409/410/422) → `InvalidConfiguration` (permanent until spec is fixed).

### Status phases

| Phase | Meaning |
|---|---|
| `Processing` | Controller is actively reconciling |
| `Succeeded` | Aggregator accepted the registration |
| `BackingOff` | Transient error; controller will retry with exponential back-off |
| `InvalidConfiguration` | Permanent failure; spec must be corrected |

## Getting Started

### Local development (kind)

To spin up a full local environment (kind + aggregator-mock + operator) see
**[hack/README.md](hack/README.md)**.

### Prerequisites
- go version v1.26+
- docker version 17.03+.
- kubectl version v1.11.3+.
- Access to a Kubernetes v1.11.3+ cluster.

### To Deploy on the cluster
**Build and push your image to the location specified by `IMG`:**

```sh
make docker-build docker-push IMG=<some-registry>/dbaas-operator:tag
```

**NOTE:** This image ought to be published in the personal registry you specified.
And it is required to have access to pull the image from the working environment.
Make sure you have the proper permission to the registry if the above commands don’t work.

**Install the public CRD into the cluster:**

```sh
make install
```

**Deploy the Manager to the cluster with the image specified by `IMG`:**

```sh
make deploy IMG=<some-registry>/dbaas-operator:tag
```

For internal development with alpha APIs enabled:

```sh
make install-dev
make deploy-dev IMG=<some-registry>/dbaas-operator:tag
```

> **NOTE**: If you encounter RBAC errors, you may need to grant yourself cluster-admin
privileges or be logged in as admin.

**Create instances of your solution**
You can apply the samples (examples) from the config/sample:

```sh
kubectl apply -k config/samples/
```

>**NOTE**: Ensure that the samples has default values to test it out.

### To Uninstall
**Delete the instances (CRs) from the cluster:**

```sh
kubectl delete -k config/samples/
```

**Delete the APIs(CRDs) from the cluster:**

```sh
make uninstall
```

**UnDeploy the controller from the cluster:**

```sh
make undeploy
```

## Project Distribution

Following the options to release and provide this solution to the users.

### By providing a bundle with all YAML files

1. Build the installer for the image built and published in the registry:

```sh
make build-installer IMG=<some-registry>/dbaas-operator:tag
```

**NOTE:** The makefile target mentioned above generates an 'install.yaml'
file in the dist directory. This file contains all the resources built
with Kustomize, which are necessary to install this project without its
dependencies.

2. Using the installer

Users can just run 'kubectl apply -f <URL for YAML BUNDLE>' to install
the project, i.e.:

```sh
kubectl apply -f https://raw.githubusercontent.com/<org>/dbaas-operator/<tag or branch>/dist/install.yaml
```

### By providing a Helm Chart

1. Build the chart using the optional helm plugin

```sh
kubebuilder edit --plugins=helm/v2-alpha
```

2. See that a chart was generated under 'dist/chart', and users
can obtain this solution from there.

**NOTE:** If you change the project, you need to update the Helm Chart
using the same command above to sync the latest changes. Furthermore,
if you create webhooks, you need to use the above command with
the '--force' flag and manually ensure that any custom configuration
previously added to 'dist/chart/values.yaml' or 'dist/chart/manager/manager.yaml'
is manually re-applied afterwards.

## Contributing
// TODO(user): Add detailed information on how you would like others to contribute to this project

**NOTE:** Run `make help` for more information on all potential `make` targets

More information can be found via the [Kubebuilder Documentation](https://book.kubebuilder.io/introduction.html)

## License

Copyright 2026.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
