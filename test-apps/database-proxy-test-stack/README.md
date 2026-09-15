# Database proxy test stack

The stack provides two components for database credential-rotation tests:

- `dbaas-proxy` forwards `/api` requests and rewrites mapped database endpoints to `tcp-proxy`.
- `tcp-proxy` carries database traffic and exposes an HAProxy admin port for connection control.

This stack exposes unauthenticated HAProxy administration controls and routes DBaaS control-plane traffic. Deploy it
only in an isolated, ephemeral test cluster. Do not deploy it in a shared or long-lived environment.

## Proxy configuration

| Environment variable | Purpose | Default |
|---|---|---|
| `DBAAS_PROXY_URL` | Upstream URL for forwarded `/api` requests | `http://dbaas-aggregator:8080` |
| `TCP_PROXY_HOST` | Host written into matching database connection properties | `tcp-proxy` |
| `TCP_PROXY_MAPPINGS` | Comma-separated database mappings | Empty; responses pass through unchanged |
| `HAPROXY_ADMIN_URL` | TCP address of the HAProxy admin interface | `tcp://tcp-proxy:9999` |

## Database mappings

The chart renders `DATABASE_MAPPINGS` into `TCP_PROXY_MAPPINGS` and the matching HAProxy frontends and backends:

```yaml
DATABASE_MAPPINGS:
  - name: postgresql
    listenPort: 8801
    targetHost: pg-patroni.postgres.svc.cluster.local
    targetPort: 5432
```

Each mapping becomes `<listenPort>/<name>@<targetHost>:<targetPort>`. `dbaas-proxy` matches the database type, host,
and port in a response, then replaces its host, port, and URL with the TCP proxy endpoint. Service addresses with and
without `.svc.cluster.local` are equivalent.

## Management API

`dbaas-proxy` exposes these endpoints:

| Endpoint | Effect |
|---|---|
| `POST /haproxy/shutdown-sessions/{type-or-port}` | Terminates sessions for one mapping |
| `POST /haproxy/shutdown-sessions` | Terminates sessions for every mapping |
| `POST /haproxy/cmd` with `{"cmd":"show sess"}` | Runs an HAProxy admin command |
| `POST /haproxy/frontend/{enable\|disable}/{type-or-port}` | Enables or disables one frontend |
| `POST /haproxy/frontend/{enable\|disable}` | Enables or disables every mapped frontend |
| `GET /health` | Reports readiness |
