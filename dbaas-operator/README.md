# dbaas-operator

`dbaas-operator` lets a service declare its databases, credentials, access policies and placement rules as Kubernetes
custom resources, and applies them through dbaas-aggregator.

## Documentation

- [DBaaS Operator](docs/DBaaS%20Operator.md): custom resources, architecture and links to every page
- [Installation](docs/installation/installation.md) and [parameters](docs/installation/parameters.md)
- [Onboarding a service](docs/howto/onboarding.md)
- [Status and troubleshooting](docs/status-and-troubleshooting.md)

## Development

Prerequisites: Go (the version in [`go.mod`](go.mod)), Docker, kubectl, and Kubernetes 1.32 or newer.

```sh
make test-unit    # unit tests, no envtest
make test         # unit and envtest controller tests
make lint         # make lint-fix applies fixes
```

The Kustomize setup in `config/` is for local development and envtest only; production installs use the Helm chart in
[`helm-templates/dbaas-operator/`](helm-templates/dbaas-operator).

```sh
make docker-build docker-push IMG=<registry>/dbaas-operator:tag
make install                                    # CRDs only
make deploy IMG=<registry>/dbaas-operator:tag   # controller from config/default
make undeploy uninstall                         # remove the controller and the CRDs
```

For a local kind environment with an aggregator mock, see [dev/README.md](dev/README.md). Code conventions are in
[AGENTS.md](AGENTS.md). Run `make help` to list all targets.

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
