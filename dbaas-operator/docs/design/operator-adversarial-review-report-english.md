# Adversarial review of dbaas-operator (`feat/operator-dev`, 8c8fe987)

Review of the `feat/operator-dev` branch against `main`, per the "Adversarial review: qubership-dbaas Kubernetes
operator" prompt. Deviations from the prompt noted at the start: instead of `internal/webhook/rotation_handler.go`, the
code has a leader-only poller (`internal/poller/`) that pulls the `GET /api/v3/dbaas/databases/changed` feed, and the
`DatabaseSecret` kind is named `DatabaseSecretClaim`. The review was adapted to the actual architecture.

Executed: `go test ./... -race -count=1` (all packages, including the 202 controller envtest specs — green),
`make manifests generate` (tree clean), `helm template` (fails on default values — see F-07), and a targeted Go test
for the shared rate limiter (written and run as part of this review).

## Executive summary

The operator is carefully written: the reconcile loops are idempotent, the Secret write races are handled, classifier
canonicalization (including `json.Number` precision) is well thought out, all 23 reason constants are documented, and
the CRDs carry CEL immutability rules and printer columns. It is still not ready for production in its current form.

The three main blockers:

1. **The CRDs are flag-gated Helm templates without `helm.sh/resource-policy: keep`** (F-01): a `helm upgrade` with
   `DBAAS_OPERATOR_ENABLED` unset (the default is `false`), or a `helm uninstall`, deletes all eight CRDs and,
   cascading, every CR and the owned Secrets holding the credentials of running applications.
2. **Kubernetes events effectively never work** (F-04): they are disabled by default (`K8S_EVENTS_ENABLED: false`),
   and when enabled, RBAC only allows event writes in the operator's namespace, while events attach to CRs in
   application namespaces — every POST gets Forbidden. The entire carefully built event-based diagnostic surface is
   unreachable for the on-call engineer.
3. **A CR in an unbound or foreign namespace is invisible** (F-05): no status, no conditions, no events, no metrics —
   only a line in the operator log. The documentation, meanwhile, explains an empty `PHASE` as "the controller has not
   written status yet".

The riskiest design decision is delivering credential rotations through a best-effort fan-out with an unconditionally
advancing cursor and a one-hour safety net (F-02): a single failed patch at rotation time leaves an application with a
stale password for up to an hour, and no signal (metric, event, or status) reports it.

The aggregator contract largely holds (every endpoint, DTO, TMF code, and the async semantics were verified against
the Java code line by line); the mismatches found (F-24…F-31) are about error classification: several transient
aggregator states are treated by the operator as permanent, and the CR stays stuck until a spec edit.

## Findings

### F-01 CRDs are deleted with the Helm release — cascading loss of CRs and live Secrets — CRITICAL / CONFIRMED

- **Where:** `helm-templates/dbaas-operator/templates/crd-*.yaml:1` (the `{{- if .Values.DBAAS_OPERATOR_ENABLED }}`
  wrapper), `dbaas-operator/Makefile:60-69` (`sync-helm-crds`), `helm-templates/dbaas-operator/values.yaml`
  (`DBAAS_OPERATOR_ENABLED: false` by default)
- **Trigger:** `helm upgrade` without overriding `DBAAS_OPERATOR_ENABLED` (default `false`), or `helm uninstall`.
  No CRD carries `helm.sh/resource-policy: keep` (grep across the chart: 0 hits).
- **Consequence:** Helm deletes the CRDs → the API server cascade-deletes every CR in every namespace → Secrets owned
  by `DatabaseSecretClaim` via ownerReference are garbage-collected. Running applications lose their mounted
  credentials; the aggregator keeps database records that nothing describes anymore.
- **Evidence:** chart render; the Secret cascade is confirmed by the comment in
  `config/samples/namespaced-secret-rbac.yaml:26-28` and `ctrl.SetControllerReference` in
  `databasesecretclaim_controller.go:760`.
- **Fix:** put `helm.sh/resource-policy: keep` on all CRDs and stop gating them on a values flag (or move the CRDs to
  a separate subchart or a `crds/` directory, which Helm does not delete).
- **Confidence:** CONFIRMED (render and grep); the GC cascade is standard Kubernetes semantics.

### F-02 Rotation: a failed fan-out silently leaves the application with the old password for up to an hour — HIGH / CONFIRMED (trace)

- **Where:** `dbaas-operator/internal/poller/rotation_poller.go:133-144` (the cursor advances unconditionally),
  `internal/poller/trigger.go:73-81` (patch failures are logged and skipped),
  `internal/controller/conditions.go:60` (`secretRotationSafetyNetInterval = 1 * time.Hour`)
- **Trigger:** the aggregator reports a password rotation; the annotation patch on one of the matching
  `DatabaseSecretClaim` CRs fails (API-server throttling, a conflict, a network error) — at exactly the moment the API
  server is loaded anyway. The second branch of the same defect: `matched == 0` (an index-key mismatch, a foreign
  namespace) is indistinguishable from "no claims exist".
- **Consequence:** the credentials Secret is not updated until the one-hour safety net. If the aggregator or adapter
  has invalidated the old password, the application runs with dead credentials for up to an hour. There are no
  signals: the CR stays `Ready=True`, there are no events, and no fan-out metric (`matched`/`patched`) exists — the
  result of `PatchClaimsForRotation` is discarded (`if _, _, perr := ...`).
- **Evidence:** trace of both branches; `rotation_poller.go:136` discards `matched`/`patched`.
- **Fix:** (a) do not advance the cursor past an item whose fan-out failed entirely, or keep a retry queue of failed
  CRs; (b) export `matched`/`patched`/`failed` counters and log `matched=0` at Warn level; (c) consider shortening
  the safety net for CRs that recently rotated.
- **Confidence:** CONFIRMED at full-branch trace level; not executed.

### F-03 One rate limiter shared by all controllers: cross-kind backoff interference and no global limit — HIGH / CONFIRMED (executed)

- **Where:** `dbaas-operator/cmd/main.go:197-201` — a single
  `NewTypedItemExponentialFailureRateLimiter[reconcile.Request]` instance is passed into `SetupWithManager` of all
  seven controllers; the key is only `NamespacedName`, with no kind.
- **Trigger:** two CRs of different kinds with the same namespace/name (the typical case: an `InternalDatabase` and a
  `DatabaseSecretClaim` named after the database; any workload CR named `binding` shares its key with
  `NamespaceBinding`).
- **Consequence:** failures of one kind inflate the other kind's delay; a successful reconcile of one (`Forget`)
  resets the other's accumulated backoff, so a stuck CR retries more often than advertised. The second half: replacing
  the default `DefaultTypedControllerRateLimiter` with a pure per-item limiter drops the global token bucket
  (10 rps / 100 burst), and there is no jitter — during a mass failure (the aggregator is down, 10k CRs) the first
  retry wave is synchronized and uncapped.
- **Evidence:** the executed test `TestSharedRateLimiterCrossControllerInterference` (written for this review,
  passed): after 5 failures of a key in "controller A", the first failure of the same key in "controller B" gets
  ≥ 32 s, and a `Forget` from B resets A's backoff to the base 1 s.
- **Fix:** create a separate `ctrlcontroller.Options` (with its own limiter) per controller, and restore the
  composition with a `BucketRateLimiter` (`workqueue.DefaultTypedControllerRateLimiter` with configurable base/max),
  plus jitter.
- **Confidence:** CONFIRMED (test executed; the single shared instance is per `main.go`).

### F-04 Kubernetes events work in no configuration — HIGH / CONFIRMED

- **Where:** `helm-templates/dbaas-operator/values.yaml:20` (`K8S_EVENTS_ENABLED: false` by default),
  `cmd/main.go:146,377-382` (noopRecorder), `helm-templates/dbaas-operator/templates/Role.yaml:35-40`
  (events `create,patch` only in `{{ .Values.NAMESPACE }}`)
- **Trigger:** a default install — events are disabled entirely. An install with `K8S_EVENTS_ENABLED=true` — the
  recorder posts the Event into the CR's namespace (the application namespace), while RBAC allows only the operator's
  namespace.
- **Consequence:** the Events section of `kubectl describe` on any workload CR is always empty: either noop or
  Forbidden (plus "Server rejected event" noise in the logs). Every Warning event the diagnostics in
  `docs/howto/DBaaS Operator.md` rely on does not exist for the on-call engineer.
- **Evidence:** Role render; recorder → objectNamespace trace; the comment in Role.yaml asserts the opposite
  ("events are written to the operator's own namespace").
- **Fix:** grant events `create,patch` in the ClusterRole (standard for cluster-scoped operators) and enable events
  by default; or honestly remove the event-based guidance from the documentation.
- **Confidence:** CONFIRMED (render + trace; the POST itself was not executed).

### F-05 A CR in an unbound/foreign namespace: empty status forever, and the documentation explains it wrong — HIGH / CONFIRMED (trace)

- **Where:** `dbaas-operator/internal/controller/helpers.go:143-162` (`checkOwnership` returns before the status is
  written), `internal/controller/resource_metrics.go:385-390` (`ownsNamespace` excludes the CR from metrics),
  `docs/howto/DBaaS Operator.md:648` (empty phase = "the controller has not written status yet")
- **Trigger:** a user creates an `InternalDatabase`/`DatabaseSecretClaim` in a namespace with no `NamespaceBinding`
  (or with a binding pointing at another operator instance) — the most common onboarding mistake.
- **Consequence:** `kubectl get` shows empty `PHASE`/`READY`, `describe` shows neither conditions nor events; the
  metrics do not see this CR either. The only signal is `log.InfoC "namespace %s unbound for %s %s, will retry in %s"`
  in the operator log. Following the documentation, the on-call engineer concludes "the operator is broken" and
  restarts it instead of creating a binding; an agent reading the CR can conclude nothing at all.
- **Fix:** write a `Ready=False/Reason=NamespaceNotBound` (or `ForeignNamespace`) condition with an instruction into
  the status, and document the empty phase honestly.
- **Confidence:** CONFIRMED (trace of `checkOwnership` — exit before the deferred patch).

### F-06 `X-Request-Id` is never sent to the aggregator, while the documentation promises end-to-end correlation — HIGH / CONFIRMED

- **Where:** `dbaas-operator/internal/client/aggregator_client.go:106-130` (headers: only `Accept` and auth),
  `docs/howto/DBaaS Operator.md:686, 1193-1194, 1368-1369, 1744-1745, 2311` ("Use lastRequestId to correlate with
  aggregator logs")
- **Trigger:** any incident where the on-call engineer follows the documentation and searches the aggregator logs for
  `status.lastRequestId`.
- **Consequence:** the identifier exists only in the operator's logs and statuses; the aggregator never sees it. The
  engineer draws the false conclusion "the aggregator never received the request". AGENTS.md ("Context Propagation")
  states end-to-end tracing as the explicit goal of this plumbing — the contract is unfulfilled on the only external
  call.
- **Fix:** add `X-Request-Id` from ctx in `OnBeforeRequest` (ctxmanager already carries it).
- **Confidence:** CONFIRMED (repo-wide grep: no place sets the header on an outgoing request).

### F-07 `helm template` with default values does not render (division by zero); the enabled chart is uninstallable without a profile — MEDIUM / CONFIRMED

- **Where:** `helm-templates/dbaas-operator/templates/HorizontalPodAutoscaler.yaml:33` (`divf` on `CPU_LIMIT`),
  `values.yaml` (no `CPU_REQUEST`/`CPU_LIMIT`), `values.schema.json` (`required` is empty)
- **Trigger:** `helm template/install --set DBAAS_OPERATOR_ENABLED=true` without a file from `resource-profiles/`.
- **Consequence:** `Error: ... error calling divf: decimal division by 0`. The schema catches nothing.
- **Fix:** resource defaults in `values.yaml` plus `required` in the schema.
- **Confidence:** CONFIRMED (executed).

### F-08 `restrictedEnvironment=true` removes the ClusterRole while the cache stays cluster-wide — CrashLoopBackOff — HIGH / CONFIRMED (render) / PLAUSIBLE (runtime)

- **Where:** `helm-templates/dbaas-operator/templates/ClusterRole.yaml:1`, `ClusterRoleBinding.yaml:1`
  (gated on `not .Values.restrictedEnvironment`), `cmd/main.go:149-170` (cluster-wide cache for every kind except
  `PermanentBalancingRule`)
- **Trigger:** an install with `restrictedEnvironment: true` — an option the chart itself offers (`values.yaml:16`).
- **Consequence:** the informer LIST gets Forbidden, `mgr.Start` exits, `os.Exit(1)`, the pod crash-loops. There is no
  namespace-scoped mode in the code that would make the option meaningful.
- **Fix:** either remove the option or add a real namespace-scoped cache mode.
- **Confidence:** render CONFIRMED; the runtime crash is PLAUSIBLE (not executed; informer behavior is standard).

### F-09 Fixed 5-second polling with no upper bound, no jitter, and a status write on every tick — HIGH / CONFIRMED (trace)

- **Where:** `dbaas-operator/internal/controller/internaldatabase_controller.go:50` (`pollRequeueAfter = 5s`),
  `:203-204` (`Status.LastRequestID = requestID` on every poll), `:545-551` (IN_PROGRESS — eternal requeue),
  `databasesecretclaim_controller.go:475,495` (5 s on `DatabaseNotFound`, including the post-timeout branch where the
  message contains `elapsed` and changes every cycle)
- **Trigger:** N stuck operations or waiting databases. Scale: 1000 "waiting" CRs = 200 rps against the aggregator and
  up to 200 status patches per second against the API server (`LastRequestID` is a fresh UUID on every poll, so the
  merge patch is never empty).
- **Consequence:** self-sustaining load on the aggregator and etcd, growing linearly with the number of stuck CRs;
  there is no upper bound on polling duration (while the `trackingId` lives, polling is eternal;
  tenant materialization repeats the full `ApplyConfig` + `CreateDatabase` every 5 seconds indefinitely,
  `internaldatabase_controller.go:184-188`). Plus constant `resourceVersion` churn that devalues status diffing for
  both humans and agents.
- **Fix:** a progressive poll interval (5 s → 30 s → 5 m) with jitter; do not write `LastRequestID` or the
  `elapsed` message when nothing else changed; add an analog of `databaseNotFoundTimeout` for an eternal IN_PROGRESS
  (an escalation reason without stopping the polling).
- **Confidence:** CONFIRMED (trace; the load figures are arithmetic).

### F-10 `TaskStateFailed` is always "permanent": an infrastructure provisioning failure stalls the CR forever with "fix the spec" advice — MEDIUM / PLAUSIBLE

- **Where:** `dbaas-operator/internal/controller/internaldatabase_controller.go:519-529` →
  `markPermanentFailure` → `conditions.go:19` ("Permanent error — spec must be corrected…")
- **Trigger:** the async operation fails for an adapter- or infrastructure-side reason (disk, network, adapter
  restart) — the aggregator reports `FAILED` without distinguishing the cause class.
- **Consequence:** the CR lands in `InvalidConfiguration`/`Stalled=True` until a spec change; the on-call engineer
  reads "spec must be corrected" and edits a spec that is known to be fine. There is no retry, even though the same
  payload could succeed.
- **Fix:** if the aggregator contract cannot distinguish the `FAILED` class — a timed retry with an attempt cap; and
  replace the message with the honest "operation failed on the aggregator side; change the spec to retry".
- **Confidence:** PLAUSIBLE (depends on the `FAILED` semantics on the Java side; the Go branch is fully traced).

### F-11 The Foreign→Mine transition has no safety net: a dropped fan-out leaves CRs unreconciled for up to ~10 hours — MEDIUM / PLAUSIBLE

- **Where:** `dbaas-operator/internal/controller/helpers.go:158-161` (Foreign — return with no requeue),
  `helpers.go:94-118` (`enqueueForBindingList` returns nil on a LIST error and loses the fan-out),
  `conditions.go:29-40` (the safety net is described for Unbound only)
- **Trigger:** migrating a namespace between operator instances: `spec.operatorNamespace` changes from A to B, the
  binding event arrives, but the LIST inside the map function fails (throttling — typical exactly during migration
  waves).
- **Consequence:** the ownership cache already says Mine, but none of the namespace's CRs got enqueued — no reconcile
  happens until the global resync (controller-runtime default ~10 h; `SyncPeriod` is not overridden) or a restart.
  The Unbound state has a 5-minute requeue; Foreign has none.
- **Fix:** return a long-interval requeue for Foreign as well (symmetric with Unbound), or retry the LIST inside the
  map function.
- **Confidence:** PLAUSIBLE (all branches traced; the scenario was not executed).

### F-12 Deleting an `InternalDatabase`/`ExternalDatabase` silently leaves the database in the aggregator, and nothing documents it — MEDIUM / CONFIRMED

- **Where:** these kinds have no finalizers (grep `Finalizer` across `api/v1`, `internal` — only the binding and the
  three balancing rules); `docs/howto/DBaaS Operator.md` — no statement about the missing cleanup found (all
  occurrences of "delet*", "orphan", "deregist" checked)
- **Trigger:** a user deletes the CR expecting the declaration/registration in the aggregator to disappear (by
  analogy with the balancing rules, which have finalizers and whose deletion semantics are documented in the table at
  `docs/howto/DBaaS Operator.md:1897-1901`).
- **Consequence:** the database and its declaration live in the aggregator indefinitely; recreating the CR with a
  different classifier spawns a second database. This may be a deliberate decision (not dropping data when the CR goes
  away is the right call), but the contrast with the balancing rules and the documentation's silence guarantee wrong
  expectations.
- **Fix:** a paragraph in the documentation for each kind: what remains in the aggregator after `kubectl delete` and
  how to actually delete the database.
- **Confidence:** CONFIRMED (code + documentation grep).

### F-13 `reason=AggregatorError` is overloaded; "will re-submit on next reconcile" promises what the backoff will not deliver — MEDIUM / CONFIRMED (trace)

- **Where:** `dbaas-operator/internal/controller/internaldatabase_controller.go:464-474` (404 on trackingId →
  reason `AggregatorError`, `return ..., err`)
- **Trigger:** the aggregator lost or cleaned up the operation (aggregator pod restart).
- **Consequence:** for a reason-keyed agent, "operation lost, auto-resubmitting" is indistinguishable from "the
  aggregator returns 5xx"; the message promises a resubmit "on next reconcile", but returning the error puts it behind
  exponential backoff (up to 5 minutes — or more, factoring in F-03).
- **Fix:** a dedicated reason (`OperationLost`); return `RequeueAfter` instead of an error.
- **Confidence:** CONFIRMED (trace).

### F-14 The requestId in every event message: the recorder never aggregates repeats — MEDIUM / CONFIRMED (trace)

- **Where:** `dbaas-operator/internal/controller/helpers.go:263-265, 273-275, 287-288`; the same pattern in
  internaldatabase (`:459-460, :472-473, :485-486, :527-528, :541-542`) and databasesecretclaim
  (`:473-474, :487-489`)
- **Trigger:** any retried failure — every attempt generates an event with a fresh `requestId=...` in the message.
- **Consequence:** Kubernetes event deduplication (count/aggregation) never kicks in — instead of one event with a
  counter, the on-call engineer sees hundreds of unique ones; a backoff storm floods the namespace with events.
  (Relevant once F-04 is fixed.)
- **Fix:** keep the requestId in `status.lastRequestId` (it is already there); drop it from the event text.
- **Confidence:** CONFIRMED (message format trace).

### F-15 Retried transient failures are logged at error level; a storm during an aggregator outage; double logging — MEDIUM / CONFIRMED (trace)

- **Where:** `externaldatabase_controller.go:169`, `internaldatabase_controller.go:156`, the poller
  (`rotation_poller.go:128` — every 30 s), plus returning the err makes controller-runtime log it again through the
  logr bridge
- **Trigger:** a ten-minute aggregator outage with 10k CRs.
- **Consequence:** an error storm (two lines per attempt) that trains the reader to ignore the error level;
  `LOG_LEVEL` changes only with a pod restart (env variable, `Deployment.yaml:107-108`).
- **Fix:** transients at Warn with an alert on the metric; or rate-limit the error lines.
- **Confidence:** CONFIRMED (logging path trace).

### F-16 Empty status patches are not suppressed: EDB writes status every 10 minutes for every CR — MEDIUM / CONFIRMED (trace)

- **Where:** `externaldatabase_controller.go:164` (`LastRequestID = requestID` on every resync) + the unconditional
  deferred `patchStatusOnExit`; only NamespaceBinding has the `DeepEqual` guard
  (`namespacebinding_controller.go:107`)
- **Trigger:** the routine 10-minute resync (`externalDatabaseDefaultResync`).
- **Consequence:** 10k EDBs → ~17 status writes per second to etcd in a perpetual steady state, plus an
  "external database registered successfully" log line and a PUT to the aggregator every cycle.
- **Fix:** the same guard NamespaceBinding has (do not patch without changes, do not update LastRequestID on a
  no-op).
- **Confidence:** CONFIRMED (trace).

### F-17 readyz = `healthz.Ping`: the pod is "ready" before cache sync and with completely broken RBAC — MEDIUM / CONFIRMED

- **Where:** `cmd/main.go:308-315`
- **Trigger:** any rollout; especially a large cluster (cache sync takes minutes) or F-08.
- **Consequence:** a rollout of a completely nonfunctional operator looks green; the leader moves to a pod that
  cannot yet serve.
- **Fix:** `mgr.AddReadyzCheck("cache-sync", ...)` on `mgr.GetCache().WaitForCacheSync` (standard controller-runtime
  plumbing).
- **Confidence:** CONFIRMED.

### F-18 An HPA on an active-passive operator; `REPLICAS: 1` versus `HPA_MIN_REPLICAS: 2`; a 3750% target — MEDIUM / CONFIRMED

- **Where:** `resource-profiles/prod.yaml`, `dev-ha.yaml`; rendered `HorizontalPodAutoscaler.yaml`
  (`averageUtilization: 3750`)
- **Trigger:** every `helm upgrade` with the prod profile.
- **Consequence:** the deploy resets replicas to 1, the HPA brings them back to 2 — one pod churns on every deploy;
  scaling to 5 adds only idle standbys (the reconcile loops run on the leader only; after the rotation poller moved
  into a leader-only Runnable, the HPA lost even that justification); a 3750% utilization target never fires.
- **Fix:** remove the HPA, drop `spec.replicas` from the Deployment when the HPA is on, run HA as a fixed 2 replicas.
- **Confidence:** CONFIRMED (render).

### F-19 All four resource profiles are identical: a 128Mi limit against cluster-wide informers of 7 kinds — MEDIUM / PLAUSIBLE

- **Where:** `resource-profiles/{dev,dev-ha,prod-nonha,prod}.yaml` (`MEMORY_LIMIT: 128Mi`, `CPU_REQUEST: 10m`)
- **Trigger:** 10k CRs / 500 namespaces: the informer stores of seven kinds plus indexes plus deserialization —
  plausibly 100–250 MB.
- **Consequence:** an OOM loop preceded by GOMEMLIMIT-induced GC thrashing; the prod profile differs from dev only in
  the replica count.
- **Fix:** scale the prod profile; document the "memory ~ #CRs" formula.
- **Confidence:** PLAUSIBLE (an estimate, not a measurement).

### F-20 The migration guide does not answer the coexistence question with core-operator — MEDIUM / CONFIRMED

- **Where:** `docs/howto/migrate-declarations-from-core-operator.md` (the checklist ends at "Apply, then verify
  `status.phase: Succeeded`"; grep for "coexist/cutover/disable/uninstall" — empty)
- **Trigger:** a migrated-but-not-deleted legacy declaration: both operators apply declarations for the same
  classifier to the same aggregator indefinitely.
- **Consequence:** the guide says neither when to turn off core-operator, nor what to delete, nor whether the two
  payloads can diverge and fight. This is the only document the migration executor will rely on.
- **Fix:** cutover steps: delete the legacy resource, disable core-operator, the order, and the verification
  criteria.
- **Confidence:** CONFIRMED (grep).

### F-21 Half the chart is pinned to `.Values.NAMESPACE` (default `default`), half follows the release namespace — MEDIUM / PLAUSIBLE

- **Where:** `templates/Role.yaml:6`, `RoleBinding.yaml:6`, `Service.yaml:6`, `ServiceAccount.yaml:5`,
  `PodMonitor.yaml` versus `Deployment.yaml`/`ClusterRole.yaml` (no `metadata.namespace`)
- **Trigger:** `helm install -n dbaas` without `NAMESPACE=dbaas`.
- **Consequence:** the Deployment lands in `dbaas`, its ServiceAccount and lease Role in `default`:
  "serviceaccount not found", then Forbidden on the lease.
- **Fix:** `{{ .Release.Namespace }}` everywhere.
- **Confidence:** PLAUSIBLE (the render confirms the layout; the install itself was not executed).

### F-22 Raw response bodies and Go errors in condition messages — MEDIUM / CONFIRMED (trace)

- **Where:** `internal/client/types.go:281-286` (`UserMessage` falls back to the entire raw body),
  `helpers.go:281-288` (the network stack's `err.Error()` goes into the message and the event)
- **Trigger:** a non-TMF response (proxy HTML, a Quarkus mapper stack trace) or a network failure.
- **Consequence:** the condition message is
  `Post "http://dbaas-aggregator:8080/...": dial tcp ...: connect: connection refused` or an entire HTML page; the
  reader gets noise instead of an action, and the status size fluctuates.
- **Fix:** truncate the fallback to N characters plus a static prefix "aggregator returned non-TMF error (HTTP %d)".
- **Confidence:** CONFIRMED (trace).

### F-23 There are no alerts at all — MEDIUM / CONFIRMED

- **Where:** the only `PrometheusRule` in the repository belongs to the aggregator
  (`helm-templates/dbaas-aggregator/templates/PrometheusRule.yaml`)
- **Consequence:** stalls, auth failures, and Secret read errors are discovered only by watching Grafana.
- **Fix — the minimal set:**
  `Stalled=True` for > 15 m; the `server_error|network_error` share of `dbaas_aggregator_requests_total` > 20% over
  10 m; `auth_error` > 0 over 10 m; `dbaas_secret_resolution_errors_total` > 0 over 15 m;
  `time() - dbaas_secret_claim_first_not_found_timestamp_seconds > 600`;
  `dbaas_resource_collector_success == 0` for 10 m.
- **Confidence:** CONFIRMED.

### F-24 Contract: a 409 on get-or-create caused by a race is classified as a permanent spec rejection — MEDIUM / CONFIRMED

- **Where (Go):** `internal/client/types.go:308-314` (`IsSpecRejection` includes 409),
  `internal/controller/helpers.go:268-276`, the `materializeTenantDatabaseIfPinned` call site
  (`internaldatabase_controller.go:241-256`)
- **Where (Java):** `AggregatedDatabaseAdministrationService.java:252-253` (`DBCreationConflictException` on a unique
  violation whose duplicate is already gone), `:451-452` (a bare 409 "Already has such database.")
- **Trigger:** a concurrent insert/drop race on `PUT /api/v3/dbaas/{ns}/databases`.
- **Consequence:** the `InternalDatabase` goes `InvalidConfiguration`/`Stalled=True` forever over a transient
  condition; only a spec edit or an operator restart heals it.
- **Fix:** for get-or-create, treat 409 as transient (retry), not as a spec rejection.
- **Confidence:** CONFIRMED (both sides traced).

### F-25 Contract: an existing database with `backupDisabled=true` makes tenant materialization an unfixable 400 — MEDIUM / CONFIRMED

- **Where (Go):** `internal/client/types.go:105-109` — `CreateDatabaseRequest` cannot express `backupDisabled`;
  Java defaults it to `false`
- **Where (Java):** `AggregatedDatabaseAdministrationService.java:99-100, 216-217`, `DBaaService.java:183-188`
  (400 "…unmodified fields (backupDisabled) can not be modified")
- **Trigger:** a pinned-tenant `InternalDatabase` whose concrete database was created by a runtime client with
  `backupDisabled=true`.
- **Consequence:** a permanent `InvalidConfiguration` that cannot be fixed from the spec — no CRD field exists that
  would satisfy the aggregator.
- **Fix:** either a `backupDisabled` field in the CRD, or a get-or-create on the Java side that skips the
  unmodifiable-field comparison.
- **Confidence:** CONFIRMED.

### F-26 Contract: classifier `scope` is case-insensitive in Go and case-sensitive in Java; declarative apply does not validate it — MEDIUM / CONFIRMED

- **Where (Go):** `api/v1/common_types.go:43-46` (a free-form string), `internaldatabase_controller.go:242`
  (`strings.EqualFold(scope, "tenant")`)
- **Where (Java):** `DeclarativeDbaasCreationService.java:150-169` (only key presence is checked),
  `AggregatedDatabaseAdministrationService.java:487-491`, `Constants.java:25-26` (exactly `service`/`tenant`)
- **Trigger:** `spec.classifier.scope: "Tenant"` — passes CRD admission and declarative apply.
- **Consequence:** the declaration is accepted and stored with a classifier no runtime client will ever match; the
  operator's follow-up `PUT /databases` gets 400 → a permanent `InvalidConfiguration` *after* a successful apply; a
  `DatabaseSecretClaim` with the same scope gets a 400 on get-by-classifier.
- **Fix:** a `service|tenant` enum in the CRD (CEL/kubebuilder validation) and dropping the `EqualFold` in the
  controller.
- **Confidence:** CONFIRMED.

### F-27 Contract: blue-green "no database in the active namespace" arrives as a 500, not a 404 — MEDIUM / CONFIRMED

- **Where (Java):** `AggregatedDatabaseAdministrationControllerV3.java:177-184` →
  `NoDatabaseInActiveNamespaceException` (CORE-DBAAS-4041) → `ErrorCodeExceptionMapper` → **500**
- **Where (Go):** `databasesecretclaim_controller.go:457-500` — only 404 + CORE-DBAAS-4006 enters the
  `DatabaseNotFound` branch
- **Trigger:** a claim on a not-yet-existing database in a controller namespace of a BG composite.
- **Consequence:** instead of the intended `DatabaseNotFound` → `DatabaseNotFoundTimeout` progression — an endless
  `AggregatorError`/BackingOff, with error-level 500s in the aggregator logs; semantically this is a not-found.
- **Fix:** map CORE-DBAAS-4041 to 404 on the Java side, or teach Go to recognize that code as a not-found.
- **Confidence:** CONFIRMED.

### F-28 Contract: 404 CORE-DBAAS-4021 (role absent from connectionProperties) is indistinguishable from a generic outage — LOW / CONFIRMED

- **Where:** Java `ConnectionPropertiesUtils.java:29-31` + its mapper → 404 CORE-DBAAS-4021;
  Go `types.go:320-322` recognizes only CORE-DBAAS-4006
- **Consequence:** a claim with a `spec.userRole` the database will never have retries forever under
  `AggregatorError`, never reaching the "waiting" UX with its timeout.
- **Fix:** include 4021 in `IsDatabaseNotFound`, or add a dedicated reason.
- **Confidence:** CONFIRMED.

### F-29 Contract: a 403 from `@RolesAllowed` becomes a permanent failure with an empty message — LOW / PLAUSIBLE

- **Where:** Go `types.go:296-314` (every 403 is a spec rejection; `UserMessage` falls back to an empty body);
  Java `application.properties:80` plus per-endpoint `@RolesAllowed` (a framework 403 with no TMF body)
- **Trigger:** the `dbaas-operator` user loses the `DB_CLIENT`/`CLUSTER_OPERATOR` role (a users.json edit, an M2M
  mapping gap).
- **Consequence:** every CR lands in `InvalidConfiguration`/`Stalled` with an empty message; fixing the credentials
  does not unstick them until a spec touch or a restart (a 401, by contrast, is retried).
- **Fix:** treat a 403 without a TMF body as an auth error (transient); with a body, as a spec rejection.
- **Confidence:** PLAUSIBLE (the framework's 403 behavior was not traced line by line).

### F-30 Contract: `settings` narrowed to `map[string]string` — LOW / CONFIRMED

- **Where:** Go `api/v1/internaldatabase_types.go:87` versus Java `DatabaseDeclaration.java:26-27`
  (`Map<String, Object>`)
- **Consequence:** declarations that need structured settings values (lists, nested objects — extension lists, for
  example) cannot be expressed through the CRD, though the declarative API accepts them.
- **Confidence:** CONFIRMED.

### F-31 Contract: a synchronous create against the client's 30-second timeout — LOW / CONFIRMED

- **Where:** Go `aggregator_client.go:36` (30 s), `CreateDatabase` never sends `?async=true`;
  Java `AggregatedDatabaseAdministrationControllerV3.java:109-110` (async only via the parameter)
- **Consequence:** a slow adapter (> 30 s) fails the first materialize call with a client timeout; it converges via
  the retry (unique violation → 200/202), but the first attempt burns a full timeout and logs an error. Go's
  202/pending branch is already in place — passing `async=true` is enough.
- **Confidence:** CONFIRMED.

### F-32 Deployment small findings — LOW

- An unconditional `nodeSelector: region: database` with no off switch (`Deployment.yaml:54-55`) — on a cluster
  without such nodes the pod is permanently Unschedulable. CONFIRMED.
- `deployment.netcracker.com/sessionId` is unquoted in `Deployment.yaml:15` and the HPA — a numeric
  `DEPLOYMENT_SESSION_ID` breaks the manifest. CONFIRMED.
- The Service names port 8080 `web`, while in the pod `web` is 8081: a ServiceMonitor pointed at the Service port
  gets health endpoints instead of metrics. CONFIRMED.
- No PodDisruptionBudget or priorityClass; topologySpread is `ScheduleAnyway`. CONFIRMED.
- `readOnlyRootFilesystem: true` versus an entrypoint that writes ca.crt into the image trust store — needs a
  one-time runtime check of the base image. PLAUSIBLE.
- The per-namespace Secret Role (`config/samples/namespaced-secret-rbac.yaml`) ships only as a sample; a namespace
  with a binding but without the Role gets a runtime Forbidden on a rare path. CONFIRMED (deliberate design, but
  onboarding is not verified by anything).
- The `namespace` label on `dbaas_secret_resolution_errors_total` versus `resource_namespace` everywhere else — two
  label vocabularies in one dashboard. CONFIRMED.
- A stale ownership cache on a non-leader (the binding reconciler updates the cache only on the leader) → after a
  rebinding, a non-leader can emit phantom or missing resource-gauge series until its restart;
  `max without (pod)` in the dashboard merges them with the correct ones. PLAUSIBLE.

## Operability assessment

A drill across seven failure modes (the strings are verbatim; the emission sites are in the findings above):

| Failure mode | What `kubectl describe` shows | Clear to a stranger? | Clear to an agent? | Gap |
| --- | --- | --- | --- | --- |
| Aggregator down | `Phase: BackingOff`; `Ready=False/AggregatorError`, message = raw `err.Error()` with the URL; `Stalled=False` "Transient error — the controller will retry automatically." | Yes — Stalled answers "retrying" | Mostly | Events are empty by default (F-04); the message is a raw transport error (F-22) |
| 400 with a TMF body | `Phase: InvalidConfiguration`; `Ready=False/AggregatorRejected`, message = TMF message; `Stalled=True` "Permanent error — spec must be corrected…" | Yes | Yes | A non-TMF body lands in the message wholesale (F-22) |
| Credentials Secret missing (EDB) | `Phase: BackingOff`; `Ready=False/SecretError` "connectionProperties[0]: get Secret "x": secrets "x" not found" + a metric | Yes | Yes | Nothing material |
| `spec.secretName` collision | Younger CR: `InvalidConfiguration`/`SecretConflict` "another DatabaseSecretClaim "a" … (older claimant wins)"; auto-recovery via the watch | Yes | Yes | The best-handled case in the codebase; documented with a state matrix |
| Namespace unbound / foreign | **Nothing**: empty PHASE/READY, no conditions, no events, no metrics; only a line in the operator log | **No** | **No** | F-05; the docs explain the empty phase as "not yet" |
| Async stuck / trackingId lost | Stuck: `WaitingForDependency`/`ProvisioningStarted` "database provisioning started asynchronously" — forever, with no escalation. 404: reason `AggregatorError`, "operation trackingId not found — will re-submit on next reconcile" | Partly | Partly | No timeout escalation for IN_PROGRESS (F-09); the 404 resubmit masquerades as AggregatorError and promises what the backoff will not deliver (F-13) |
| Rotation with no matches / partial fan-out | **Silence**: `matched` is discarded; a patch failure is a log line, and the cursor moves on; healing is the one-hour safety net | **No** | **No** | F-02: no metric, no event, no status |

**Reason coverage.** All 23 reason constants (`events.go` plus the condition-only ones) are documented in
`docs/howto/DBaaS Operator.md` with meaning and condition polarity — none is uncovered (a rare result). Residual gaps:
`AggregatorError` covers four different situations (5xx, network, a lost trackingId, a balancing-rule cleanup failure)
that cannot be told apart by the reason alone; the empty phase is documented only for "just created" (see F-05).

**The most-missed signals:** (1) a rotation fan-out metric or event (`matched/patched/failed`); (2) a status reason
for an unbound/foreign namespace; (3) an escalation reason "async stuck > T"; (4) a `dbaas_operator_build_info` gauge
with the version — "did the last rollout make it worse" is currently answered only by deploy timestamps.

## Checked and sound

Attacked and not broken:

- `make manifests generate` — the tree is clean; the Helm CRD copies are byte-identical to `config/crd/bases`; RBAC
  markers ↔ `config/rbac/role.yaml` ↔ Helm agree verb for verb (except events, F-04).
- `go test ./... -race` — green (202 envtest specs); no races found in the trigger-stamp maps or the ownership cache;
  stamp cleanup on delete/foreign is correct (the map-leak hypothesis did not hold).
- The "a Secret informer starts despite `DisableFor`" hypothesis is refuted: there are no Secret watches at all;
  `DisableFor` concerns only the client cache; the claimed OOM protection works.
- `requestIDFromContext` cannot panic: both call sites run under `initReconcileContext` in the same Reconcile.
- `silentEventsTransport` intercepts strictly paths with the `/events` suffix (core and events.k8s.io) — leader
  election lease requests are untouched.
- Classifier canonicalization: `ClassifierFlatMap`/`ClassifierIndexKey`/`classifierFromMap` form a closed round-trip;
  large integers survive via `json.Number` on both ends; `customKeys`/`extraKeys`/reserved-key collisions are
  validated in preflight.
- The `DatabaseSecretClaim` sibling tiebreak (older wins, UID on equal timestamps) converges on both peers;
  create/update/delete races around the target Secret are handled honestly (create → refetch → retry → recreate).
- `classifier.namespace ≠ metadata.namespace` is rejected before the aggregator in all three kinds (the "operator
  acts on a foreign namespace" hypothesis is refuted).
- Balancing-rule finalizers: added before the external effect; deletion retries the cleanup; partial failures keep
  `appliedRules` for a later sweep.
- The dashboard is built only from actually exported metrics; multi-replica series deduplication is in place
  (`max without (pod, instance)`) and documented.
- Rotation poller: leader-only; the keyset cursor seeded at the high-water mark closes the "rotation before startup"
  window; the "seed before the startup-reconcile drain" ordering is correct.
- Metric naming follows the Prometheus conventions; no label carries a CR name anywhere except the deliberate
  KSM-style gauges.

Contract pairs verified against the Java side and found consistent (both sides traced):

- `POST /api/declarations/v1/apply`: paths, 200/202 (202 ⇔ `trackingId`), all DTO fields of both subKinds, TMF codes
  400 CORE-DBAAS-4035/4036; the `TaskState` strings match `normalizeStateName` verbatim; the `DataBaseCreated`
  condition matches the Go constant.
- `GET /operation/{id}/status`: 200-only; an unknown id → 404 CORE-DBAAS-7002, Go correctly clears the trackingId and
  resubmits; the operation store is DB-backed, so ids survive an aggregator restart (the "the aggregator loses
  operations on restart" hypothesis is withdrawn on this point; the TERMINATED branch already covers it).
- `registration/externally_manageable`: 200/201, DTOs match, `role` is always present, an empty CP list is cut off by
  the CRD.
- `PUT /databases`: 200/201/202, `originService` is always set, tenant isolation is disabled for CLUSTER_OPERATOR on
  the Java side.
- `get-by-classifier`: the DTO is a superset of the Go struct; 404 CORE-DBAAS-4006 ↔ `IsDatabaseNotFound`; the bare
  BG 404 is a documented transient.
- All three balancing-rule APIs: statuses and DTOs match.
- `GET /databases/changed`: parameters, strictly-greater keyset semantics, ISO-8601 ↔ RFC3339Nano, the limit of 500
  inside Java's 1..1000 bound; the `CLUSTER_OPERATOR` role is granted to the operator in both auth modes; the feed is
  built from live DB rows, so there is no retention window to outlive.
- TMF parsing (`detail` → `message`); the M2M token audience `dbaas` matches `application.properties:95`; a 401 is
  transient in both modes.

## Open questions

1. Is it intended that deleting an `InternalDatabase`/`ExternalDatabase` does not deregister the database in the
   aggregator (F-12) — and if so, where should that be written down?
2. Can `POST /api/declarations/v1/apply` for subKind=DbPolicy return 202 + `trackingId`?
   `DatabaseAccessPolicyReconciler` discards the response and sets Succeeded immediately
   (`databaseaccesspolicy_controller.go:106`) — if the async branch is reachable, `Ready=True` is set before the
   policy is actually applied.
3. Is a repeat of `apply` after a client timeout idempotent when the aggregator has already accepted the operation
   (a lost 202): does a second operation appear, or is the same one reused?
4. Does the aggregator distinguish "bad spec" from "infrastructure failed" inside `TaskState=FAILED` (for F-10)?
5. Two operator instances in different namespaces, both with a `PermanentBalancingRule` targeting the same
   namespaces: whose `DELETE .../rules/permanent` wins? This kind has no ownership check by design.
6. `restrictedEnvironment` (F-08): is it a live option or template heritage? If live — what cache mode was intended
   for it?
