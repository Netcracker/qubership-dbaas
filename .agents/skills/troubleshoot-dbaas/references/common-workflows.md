# DBaaS common troubleshooting workflows

Use this reference when evidence crosses DBaaS components. It routes to the
canonical cases in `aggregator.md` and `operator.md`; it does not repeat their
diagnosis or remediation.

## Identify the owning component

- Deployment, hook Jobs, registry storage, adapter registration, balancing,
  `CORE-DBAAS-*`, lost databases, and ghost databases belong to
  dbaas-aggregator. Load the matching case in `aggregator.md`.
- Resources in API group `dbaas.netcracker.com` belong to dbaas-operator. Load
  the matching CR phase or reason case in `operator.md`.
- An adapter health state `PROBLEM` is reported by dbaas-aggregator, but an
  adapter crash or database-engine error belongs to the troubleshooting skill
  for that database type. Use `aggregator.md` section
  `Physical database adapter reports a problem` only to identify this boundary.

Do not infer ownership from the final component that reports a failure. Follow
the request or resource identifiers across the evidence first.

## Correlate an operator CR with Aggregator logs

For a `dbaas.netcracker.com` CR:

1. Read `status.phase`, both `Ready` and `Stalled` conditions, the condition
   `reason`, `status.lastRequestId`, and `status.trackingId` from the attached CR.
2. Match `status.lastRequestId` exactly to `request_id=<value>` in attached
   dbaas-aggregator logs.
3. If `status.trackingId` is present, follow that operation through later
   Aggregator messages before classifying the state as stuck.
4. Route the exact phase and reason to the canonical case in `operator.md`.
   Use the Aggregator evidence to explain the downstream failure; do not replace
   the CR's recorded reason with a guess.

`Stalled=True` means dbaas-operator stops retrying until the resource generation
changes. It does not prove that the underlying failure is a permanent
specification error: for reason `AggregatorRejected`, correlate the Aggregator
request or operation before choosing a remediation. `Ready=False` with
`Stalled=False` is retried by the operator. Reason `OperationTerminated` on an
`InternalDatabase` is not a failure: the operator resubmits after the Aggregator
operation is cancelled or lost during a restart.

## Trace logical database provisioning

Use the first exact failure in the request path:

1. A physical database selection error routes to one of these `aggregator.md`
   cases:
   - `Database creation fails with Requested physical database is not registered`
   - `Database creation fails with No balancing rule exists to determine physical database`
   - `Microservice balancing rule points to a label that matches no physical database`
2. An adapter call error routes to `aggregator.md` section
   `Adapter registration handshake returns Request to adapter failed. Status code`, or
   to the database-type troubleshooting skill when the attached adapter or
   engine log contains the initiating failure.
3. A dbaas-operator CR phase or reason routes to `operator.md`. Correlate its
   request ID before concluding that `InvalidConfiguration` describes the
   original cause.

## Trace credential changes

There are two separate credential paths:

- PostgreSQL registry credentials are read at runtime from Secret
  `dbaas-storage-credentials`. If changed deployment parameters appear to have
  no effect, load `aggregator.md` section
  `Changed registry credentials in deployment parameters have no effect`.
- DBaaS API users are rendered into `users.json` in Secret
  `dbaas-security-configuration-secret` and loaded by dbaas-aggregator at
  startup. A `401` after rotation routes to `aggregator.md` section
  `Adapters or clients get 401 from dbaas-aggregator after a credential change`.
- A dbaas-operator `Unauthorized` reason routes to `operator.md` section
  `DBaaS custom resource is BackingOff with reason Unauthorized`.

Do not delete either credential Secret to force regeneration. The exact safety
rules remain in `SKILL.md` under `Forbidden actions`.

## Distinguish registry consistency failures

- A database present in the DBaaS registry but absent from the physical cluster
  is **lost**. Load `aggregator.md` section
  `Application cannot connect although DBaaS returns connection properties`.
- A database present in the physical cluster but absent from the DBaaS registry
  is **ghost**. Load `aggregator.md` section
  `Ghost databases reported in the physical database`.

Never apply the lost-database remediation to a ghost database or the ghost
cleanup to a lost database.
