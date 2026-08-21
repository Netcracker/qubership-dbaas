# Database proxy test stack

A reusable fixture that puts two controllable proxies into the DBaaS control and data paths, so a
test can interrupt an application's live database connection on demand.

Deploy it with:

```bash
test-apps/database-proxy-test-stack/deploy.sh \
  --namespace dbaas \
  --image dbaas-proxy-stub:it \
  --mapping "postgresql:8801:pg-patroni.postgres.svc.cluster.local:5432"
```

`GoPostgresSecretRotationIT` is the first consumer. The chart itself holds no service name,
classifier, Secret name, or assertion, so Spring and Quarkus rotation tests can reuse it unchanged:
DBaaS Proxy matches connection properties by database type, host, and port, which is independent of
who asked for them.

## Why two proxies

HAProxy can only terminate a connection the application actually opened through it. Nothing makes an
application do that by itself, because the operator writes whatever endpoint the aggregator returns
into the generated Secret. DBaaS Proxy solves that by rewriting the endpoint before the operator ever
sees it.

```text
Control plane                          Data plane

dbaas-operator                         application
      |                                     |
      | /api requests                       | PostgreSQL protocol
      v                                     v
 dbaas-proxy  --- admin commands --->  tcp-proxy (HAProxy)
      |            (port 9999)              |
      | forwards /api,                      |
      | rewrites DB endpoints               |
      v                                     v
 dbaas-aggregator                      PostgreSQL
```

- **dbaas-proxy** forwards every `/api` request to the aggregator unchanged, and rewrites the host
  and port of matching connection properties in create and get-database responses to the TCP proxy.
  It carries no database traffic.
- **tcp-proxy** is HAProxy in TCP mode. It owns the sessions the test terminates, and exposes an
  admin socket on port 9999 for dbaas-proxy.

**Deploy this before any `InternalDatabase` or `DatabaseSecretClaim` exists.** A claim reconciled
before dbaas-proxy is in the control path gets a direct-to-database endpoint, and its traffic never
reaches HAProxy. Route the operator through the proxy at the same time:

```bash
kubectl -n dbaas set env deployment/dbaas-operator \
  DBAAS_AGGREGATOR_URL="http://dbaas-proxy.dbaas:8080"
kubectl -n dbaas rollout status deployment/dbaas-operator --timeout=300s
```

That is test routing, not an operator change. The operator chart does not expose
`DBAAS_AGGREGATOR_URL`, so the deployment is patched directly.

## Choosing the proxy image

The chart takes the image as a value and does not care which of these it deploys. Both expose the
same environment variables and HTTP endpoints.

| Image | Source | Use it when |
|---|---|---|
| `dbaas-proxy-stub` | [this repository](../dbaas-proxy-stub) | Default; the only option in public CI |
| `dbaas-proxy` | Internal GitLab, via `build-image.sh` | Validating against the production component |

Build the stub the same way CI does:

```bash
docker build -t dbaas-proxy-stub:it test-apps/dbaas-proxy-stub
kind load docker-image dbaas-proxy-stub:it --name kind
```

The real dbaas-proxy lives on the internal GitLab, builds against internal Go modules, and publishes
to an internal registry, so a public runner can neither pull nor build it. The stub implements the
subset the rotation test exercises; its source comments list what it leaves out.

### Packaging the real dbaas-proxy

Run this where the internal Go module resolves. It compiles the binary with the host toolchain — the
way upstream builds it — and packages the result on the public platform base image, so the resulting
image pulls nothing from an internal registry.

```bash
test-apps/database-proxy-test-stack/build-image.sh \
  --source ~/DBaaS/dbaas-proxy \
  --ref origin/master \
  --image dbaas-proxy:local \
  --kind kind
```

Pass `--binary <path>` to package a binary that was compiled elsewhere. That covers the split case
where Go modules resolve on one host and Docker runs on another.

## Values

| Value | Purpose | Default |
|---|---|---|
| `NAMESPACE` | Namespace for every object in the chart | `dbaas` |
| `DBAAS_PROXY_IMAGE_REPOSITORY` / `DBAAS_PROXY_TAG` | Proxy image; both required | — |
| `DBAAS_PROXY_UPSTREAM_URL` | Aggregator that dbaas-proxy forwards `/api` to | `http://dbaas-aggregator.dbaas:8080` |
| `TCP_PROXY_IMAGE` | HAProxy image, pinned by digest | `haproxy:3.0-alpine` |
| `TCP_PROXY_ADMIN_PORT` | HAProxy admin socket, cluster-internal | `9999` |
| `TCP_PROXY_HOST` | Address rewritten into connection properties | `<TCP_PROXY_SERVICE_NAME>.<NAMESPACE>` |
| `DATABASE_MAPPINGS` | Proxied databases; see below | one PostgreSQL entry |

`TCP_PROXY_IMAGE` carries a digest alongside the tag, so a rebuilt upstream tag cannot change what
the fixture runs. Update the tag and the digest together.

`DATABASE_MAPPINGS` is the single source of truth. It renders both the HAProxy frontends and the
`TCP_PROXY_MAPPINGS` that dbaas-proxy matches against, so a port cannot be listening while the
rewrite points somewhere else:

```yaml
DATABASE_MAPPINGS:
  - name: postgresql                                    # database type, as the aggregator registers it
    listenPort: 8801                                    # renders fe_8801 / be_8801 / srv_8801
    targetHost: pg-patroni.postgres.svc.cluster.local   # where the adapter says the database lives
    targetPort: 5432
```

`name` also addresses the frontend: `POST /haproxy/shutdown-sessions/postgresql` and
`POST /haproxy/shutdown-sessions/8801` are equivalent. `targetHost` must match what the adapter
returns, though `<service>.<namespace>` and its `.svc.cluster.local` form are treated as the same
host.

## Management endpoints

Reachable on `service/dbaas-proxy:8080`, and the reason the fixture exists:

| Endpoint | Effect |
|---|---|
| `POST /haproxy/shutdown-sessions/{type-or-port}` | Terminates the live sessions for one database |
| `POST /haproxy/shutdown-sessions` | Terminates them for every mapping |
| `POST /haproxy/cmd` with `{"cmd":"show sess"}` | Runs one admin command and returns its raw output |
| `POST /haproxy/frontend/{enable\|disable}/{type-or-port}` | Stops or restores new connections |
| `GET /health` | Readiness |

## Troubleshooting

- **The Secret still points at the database.** The claim was reconciled before the operator was
  routed through dbaas-proxy, or the mapping does not match what the adapter returned. Compare the
  mappings dbaas-proxy logs at startup with the `host` and `port` in the generated Secret.
- **`show sess` reports no session.** The application is connecting straight to the database, so the
  endpoint was never rewritten. Check the Secret endpoint before rotating anything.
- **Shutting down sessions changes nothing.** Confirm the frontend names. HAProxy is configured as
  `fe_<listenPort>`, and a mapping that changed port after deployment leaves the old pod running,
  because HAProxy reads its configuration only at startup.
