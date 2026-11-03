# DBaaS Operator Parameters

## Settings

Helm values of `helm-templates/dbaas-operator` that change how the operator behaves. The chart's
[`values.schema.json`](../../helm-templates/dbaas-operator/values.schema.json) lists every value, including replicas,
resources, scheduling and autoscaling.

| Value                                     | Default                        | Description                                                                                                                  |
|-------------------------------------------|--------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `DBAAS_OPERATOR_ENABLED`                  | `false`                        | Deploys the operator.                                                                                                        |
| `M2M_AUTH_MODE`                           | `legacy`                       | How the operator authenticates to dbaas-aggregator; see [Authentication](#authentication).                                   |
| `DBAAS_AGGREGATOR_URL`                    | `http://dbaas-aggregator:8080` | Base URL of dbaas-aggregator. The default resolves to the aggregator Service in the operator's own namespace. Keep the HTTP URL when [Istio Ambient Mesh](../../../docs/installation/installation.md#enable-operator-to-aggregator-mtls) secures this connection. |
| `LEADER_ELECT`                            | `true`                         | Enables leader election. Keep it on when `REPLICAS` is greater than 1.                                                       |
| `K8S_EVENTS_ENABLED`                      | `true`                         | Emits Kubernetes Events; see [Events](../status-and-troubleshooting.md#events).                                              |
| `DBAAS_ROTATION_POLL_INTERVAL`            | `30s`                          | How often the [rotation poller](../resources/database-secret-claim.md#credential-rotation) reads the changed-databases feed. |
| `DBAAS_EXTERNAL_DATABASE_RESYNC_INTERVAL` | `10m`                          | How often each `ExternalDatabase` re-reads its Secrets and registers again.                                                  |
| `DBAAS_PHYSICAL_DATABASE_RESYNC_INTERVAL` | `10m`                          | How often each `PhysicalDatabase` re-reads its Secret and registers again.                                                   |
| `LOG_LEVEL`                               | `info`                         | `debug`, `info`, `warn`, `error` or `fatal`.                                                                                 |
| `MONITORING_ENABLED`                      | `true`                         | Creates a `PodMonitor` and a Grafana dashboard. Set it to `false` when their CRDs aren't installed.                          |
| `restrictedEnvironment`                   | `false`                        | Skips cluster-scoped RBAC; see [Restricted environment](installation.md#restricted-environment).                             |
| `NAMESPACE`                               | `default`                      | The operator namespace; must be set explicitly, see [Install](installation.md#install).                                      |

The intervals are Go durations, such as `45s` or `1h`. An empty, unparseable or non-positive value is logged and the
default applies.

The chart sets `CLOUD_NAMESPACE` to the pod's namespace. The operator exits at startup when it is unset, because
[operator assignment](../DBaaS%20Operator.md#operator-assignment) depends on it.

## Authentication

`M2M_AUTH_MODE` must match the dbaas-aggregator setting of the same name. An aggregator without M2M rejects Bearer
tokens, so a mismatch fails every call with `401`.

| `M2M_AUTH_MODE` | Credential                                        |
|-----------------|---------------------------------------------------|
| `legacy`        | HTTP Basic, user `dbaas-operator`                 |
| `hybrid`, `k8s` | Projected service-account token, audience `dbaas` |

### Basic Auth (Default)

dbaas-aggregator's chart generates the `dbaas-operator` password and stores it, with the `DB_CLIENT` and
`CLUSTER_OPERATOR` roles, in `users.json` of `dbaas-security-configuration-secret`. The operator chart mounts that
Secret at `/etc/dbaas/security`. Nothing needs to be configured on either side.

- If the Secret doesn't exist in the operator namespace, the pod doesn't start (`FailedMount`).
- If `users.json` has no `dbaas-operator` entry, the operator logs a fatal error and exits.
- When the Secret changes, the operator reloads the password without a restart.

### M2M Token

The chart mounts a projected service-account token with audience `dbaas` at `/var/run/secrets/tokens/dbaas/token`.
Kubernetes rotates it, and the operator reads it on every request, so rotation needs no restart. dbaas-aggregator must
map the operator's ServiceAccount to the `CLUSTER_OPERATOR` and `DB_CLIENT` roles.

## Startup Flags

The chart sets the container arguments itself, and no Helm value changes these flags. To change one, patch the
Deployment.

| Flag                          | Default | Description                                                                                                 |
|-------------------------------|---------|-------------------------------------------------------------------------------------------------------------|
| `--http-bind-address`         | `:8080` | Address of Prometheus `/metrics`; see [DBaaS Operator Metrics](../monitoring/DBaaS%20Operator%20Metrics.md) |
| `--health-probe-bind-address` | `:8081` | Address of the liveness `/healthz` and readiness `/readyz` probes                                           |
| `--leader-elect`              | off     | Enables leader election; the chart adds it when `LEADER_ELECT` is true                                      |
| `--backoff-base-delay`        | `1s`    | First retry delay after a failed reconcile                                                                  |
| `--backoff-max-delay`         | `5m`    | Longest retry delay after a failed reconcile                                                                |

The backoff flags tune the [reconcile backoff](../status-and-troubleshooting.md#reconcile-backoff). Invalid values
fall back to the defaults; the startup log shows the delays in use, for example
`Backoff configured base=1s max=5m0s jitter=10%`.
