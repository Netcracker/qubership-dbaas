# dbaas-operator

`dbaas-operator` integrates Kubernetes with DBaaS by reconciling a family of custom resources that describe databases, credentials, access policies, and physical-database balancing rules, and driving them through dbaas-aggregator. It lets workloads declare and consume databases the Kubernetes-native way, and keeps `DatabaseSecretClaim` secrets in sync as credentials rotate.

> **Full reference:** see **[docs/howto/DBaaS Operator.md](docs/howto/DBaaS%20Operator.md)** for the complete design, status/condition reference, RBAC, authentication, and credential-rotation details. For a local kind environment see **[dev/README.md](dev/README.md)**.

## Custom Resources

All CRs are served at `dbaas.netcracker.com/v1` and installed by `make install` / the Helm chart:

| Kind | Purpose |
|---|---|
| `ExternalDatabase` | Register a pre-existing (externally managed) database in dbaas-aggregator so microservices can discover its connection details. |
| `InternalDatabase` | Provision a new logical database via dbaas-aggregator (asynchronous; polled to completion). |
| `DatabaseSecretClaim` | Materialize a database's credentials into a Kubernetes `Secret` in the workload namespace, kept in sync as credentials rotate. |
| `DatabaseAccessPolicy` | Declare per-microservice role grants and apply them to dbaas-aggregator. |
| `MicroserviceBalancingRule` / `NamespaceBalancingRule` / `PermanentBalancingRule` | Configure physical-database balancing rules in dbaas-aggregator. |
| `NamespaceBinding` | Claim a namespace for this operator instance (ownership) — gates which CRs the operator reconciles. |

## Authentication

The operator authenticates to dbaas-aggregator in one of two modes, selected by `KUBERNETES_M2M_ENABLED` and **must match the aggregator's setting**:

- `false` (default) — HTTP Basic Auth, using credentials from the chart-created `dbaas-operator-aggregator-credentials` Secret;
- `true` — a Kubernetes projected service-account token (Bearer / M2M).

Credential rotations are propagated by **polling** dbaas-aggregator's changed-databases feed (the operator exposes no inbound endpoint). See the [configuration parameters](docs/howto/DBaaS%20Operator.md#configuration-parameters) for the full list.

## Getting Started

### Prerequisites
- Go 1.26+
- Docker
- kubectl + access to a Kubernetes cluster. The CRDs use CEL validation
  (`x-kubernetes-validations`), so Kubernetes v1.25+ is required (v1.29+ recommended).

### Deployment

**Production** — the operator ships as part of the Qubership Helm chart under
[`helm-templates/dbaas-operator/`](helm-templates/dbaas-operator) (`values.yaml` +
`values.schema.json`), deployed by the platform tooling and gated on
`DBAAS_OPERATOR_ENABLED`; CRDs are rendered as chart templates. This is the
supported install path.

**Local / dev-test** — the Kustomize surface under `config/` is for local dev,
tests, and envtest only (not a production distribution). Build and push the image,
then install the CRDs and deploy via kustomize:

```sh
make docker-build docker-push IMG=<registry>/dbaas-operator:tag
make install                                    # CRDs only
make deploy IMG=<registry>/dbaas-operator:tag   # kustomize config/default
```

For a full local environment (kind + aggregator-mock + operator) and ready-made
test resources, see **[dev/README.md](dev/README.md)**.

### Uninstall (dev/test)

```sh
make undeploy     # remove the kustomize-deployed controller
make uninstall    # remove the CRDs
```

Run `make help` to list all targets.

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
