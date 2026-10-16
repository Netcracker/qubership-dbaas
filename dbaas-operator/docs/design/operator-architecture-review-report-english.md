# Architecture review: qubership-dbaas Kubernetes operator (`feat/operator-dev`, 8c8fe987)

Scope: design of `dbaas-operator/` against baseline `main`, with the aggregator
(`dbaas/dbaas-aggregator/`) read wherever the boundary is in question. The code-level adversarial
review (32 findings, `F-01`…`F-32`) is used as a symptom inventory only. Code-level defects are
dropped even where real; every finding below names a decision whose fix changes a contract, a
boundary, or a deployment unit.

## Verdict

The operator is a competently built Kubernetes front end to an aggregator that remains the sole
source of truth. That honesty is its best property: it never pretends to own state it does not own.
The cost is that it is a *submission pipeline wearing CRD clothes* — create-and-update-only,
edge-triggered, with no drift repair, no deletion semantics for the database kinds, and no stored
external identity. For a single cluster, one operator instance, and a stable aggregator contract, it
will carry the system. Each of those three qualifiers is load-bearing.

Three decisions to revisit before the first production install:

1. **Deletion and lifecycle semantics** (A-03, A-04): decide what `kubectl delete` means for every
   kind and make namespace migration an operation rather than a teardown.
2. **Drift and reconciliation** (A-05): the declarative store has three writers (this operator,
   core-operator, the blue-green engine) and no reconciler; add periodic re-apply while the apply
   endpoint is still cheap and idempotent.
3. **The contract as an artifact** (A-06): generate or contract-test the client against the Java
   DTOs — both sides live in one repository, so this is uniquely cheap here.

The single most expensive one-way door is **`v1` with a structurally frozen schema and no
conversion path** (A-01). Every other decision in this report can be revisited after GA; the schema
version and its CEL freezes are being set in concrete in every cluster that installs the chart.

## Decision inventory

The team wrote no ADRs; these are recovered from code and docs. "Reversible?" means after the first
production install, not today.

| # | Decision | Encoded in | Buys | Costs | Reversible? |
| --- | --- | --- | --- | --- | --- |
| D-01 | One CRD kind per aggregator API concept (8 kinds) | `api/v1/*_types.go`, `README.md:7-18` | Thin, auditable mapping to the API | A usable DB needs two hand-agreed CRs (A-10) | Additive only — shipped kinds are permanent |
| D-02 | Free-form classifier is the only cross-system identity; the aggregator's DB id is received and dropped | `api/v1/common_types.go:37-86`, `internal/client/types.go:218-222` | Byte-compatibility with runtime dbaas-clients | Late, remote identity failures; collisions undetected (A-02) | Yes — a status field is additive |
| D-03 | Single API version `v1`, no alpha/beta, no conversion webhook | `api/v1/groupversion_info.go`, `dbaas-operator/AGENTS.md` ("single group, no alpha versions", "no webhooks") | No conversion machinery to run | No schema-evolution path at all (A-01) | **No** |
| D-04 | Blanket CEL immutability (`self == oldSelf`) on identity *and* day-2 fields (`secretName`, `userRole`) | `api/v1/databasesecretclaim_types.go:27-44`, `internaldatabase_types.go:65,75`, `externaldatabase_types.go:89-105` | Prevents silent retargeting | Delete-and-recreate is the only mutation; freezes even field *presence* | Yes — relaxing validation is compatible |
| D-05 | Namespace sharding by opt-in `NamespaceBinding` in the target namespace, resolved via per-pod cache | `internal/ownership/resolver.go:66-158`, `internal/controller/helpers.go:143-162` | Multi-instance coexistence without config | Unbound CRs are inert and silent (A-11); migration pain (A-04) | Yes, with migration effort |
| D-06 | Binding protection finalizer blocks deletion while any workload CR exists | `cmd/main.go:211-238`, docs `DBaaS Operator.md:764-778` | No orphaned workload CRs | Combined with D-04/D-07, makes instance migration a teardown (A-04) | Yes |
| D-07 | Identity retargeting doctrine: "delete and recreate the CR" | docs `DBaaS Operator.md:142-144`, `migrate-declarations-from-core-operator.md:200-203` | Simple mental model | Recreating a `DatabaseSecretClaim` GC-deletes its Secret mid-flight | Yes |
| D-08 | Edge-triggered submission: `GenerationChangedPredicate`, no periodic re-apply for `InternalDatabase` / `DatabaseAccessPolicy` / rules | `internaldatabase_controller.go:570,578`, `databaseaccesspolicy_controller.go:153,161`, `balancingrule_controller.go:336-366` | Minimal aggregator load | Aggregator-side drift is never repaired (A-05) | Yes — cheap |
| D-09 | `ExternalDatabase` alone is level-triggered (10 min re-PUT resync) | `externaldatabase_controller.go:179,330-346` | Credential-Secret pickup without a watch | Inconsistent authority model across kinds | Yes |
| D-10 | Create/update-only lifecycle for `InternalDatabase`, `ExternalDatabase`, `DatabaseAccessPolicy`; finalizers only on balancing rules and the binding | grep `Finalizer` over `api/v1`, `internal/` — binding + 3 rule kinds only | Never destroys data by accident | `kubectl delete` is a silent no-op; GitOps prune diverges (A-03) | Semantics hard to change after users rely on no-op delete |
| D-11 | Rotation delivery: leader-only poll of a shared cluster-scoped feed, in-memory cursor, at-most-once fan-out, 1 h per-claim backstop | `internal/poller/rotation_poller.go:74-150`, `conditions.go:60` | No inbound endpoint; simple | Real staleness bound is 1 h and is promised nowhere (A-08) | Yes |
| D-12 | Operator responsibility ends at Secret content; no workload signaling | docs `DBaaS Operator.md:2149-2159` (content-aware write) | Clean separation | Rotation contract for app authors is unstated (A-08) | Yes — additive |
| D-13 | Hand-mirrored Go structs of hand-written Java DTOs; no shared schema, no contract tests, no version negotiation | `internal/client/types.go` vs `dbaas/dbaas-aggregator/.../dto/**` | No codegen toolchain | Silent skew; error-class drift already visible (A-06) | Yes — tooling |
| D-14 | Aggregator domain semantics implemented client-side: tenant materialization, scope handling, BG awareness | `internaldatabase_controller.go:241-265`, `:503-511` | Ships without aggregator changes | Duplicated semantics, 5 s-forever retry shape (A-07) | Yes — boundary change |
| D-15 | CRDs ship as chart templates gated on `DBAAS_OPERATOR_ENABLED` (default `false`) | `dbaas-operator/helm-templates/dbaas-operator/templates/crd-*.yaml:1`, `values.yaml:14`, docs `:180-184` | One chart to install | Value flip or uninstall cascades CR + Secret deletion (F-01); no CRD ownership story (A-01) | Yes — packaging |
| D-16 | One process, one shared rate limiter, one HTTP client, fixed 30 s timeout, no client retries or bulkheads | `cmd/main.go:197-201`, `internal/client/aggregator_client.go:36,108` | Simplicity | Fate-sharing across kinds and namespaces (A-14, F-03) | Yes |
| D-17 | Secrets: no informer, no cluster-wide RBAC; per-namespace opt-in Role bundle; owned Secrets GC'd via ownerReference | `cmd/main.go:180-187`, docs `:595-617`, `databasesecretclaim_controller.go:760` | Least privilege, OOM-safe | Onboarding bundle unverified (F-32); Secret dies with the claim (feeds A-04) | Yes |
| D-18 | Coexistence: core-operator and this operator write the same `database_declarative_config` rows; no origin marker | `DeclarativeController.java:83`, `ConfigControllerV1.java:67-103`, entity `DatabaseDeclarativeConfig.java:15-87` | Migration needs no aggregator change | Last-writer-wins with no arbitration (A-13) | Yes — aggregator change |
| D-19 | The blue-green engine itself copies and deletes declarative configs server-side | `BlueGreenService.java:150-153` (warmup copy), `:508` (commit delete) | BG works without the operator | A third unarbitrated writer to the store the CRs claim to describe (A-05) | Boundary decision |

## Findings

### A-01 The public API ships as `v1`, structurally frozen, with no evolution path — BLOCKER / CONFIRMED

- **Decision:** all eight kinds are served at `dbaas.netcracker.com/v1` from the first release, with
  `self == oldSelf` CEL freezes on identity fields, no `v1alpha1`/`v1beta1` history, no conversion
  webhook, and CRDs delivered as value-gated chart templates.
- **Encoded in:** `api/v1/groupversion_info.go`; `dbaas-operator/AGENTS.md` ("single group, no alpha
  versions", "no webhooks"); CEL rules at `api/v1/internaldatabase_types.go:65,75`,
  `databasesecretclaim_types.go:27-44`; chart gate
  `dbaas-operator/helm-templates/dbaas-operator/templates/crd-internaldatabases.yaml:1`.
- **Force it fails under:** any schema change after clusters hold objects. `settings` is
  `map[string]string` against the aggregator's `Map<String, Object>` (F-30), `backupDisabled` is
  missing (F-25), `scope` is an open string (F-26) — three known day-one candidates for change. A
  widened `settings` or an added enum needs either a conversion webhook that does not exist (and per
  AGENTS.md is deliberately out) or an in-place `v1` edit against stored objects, with CEL
  immutability rules that stored objects may then violate. Rollback of a chart that changed a schema
  is undefined.
- **Consequence:** the first real schema mistake becomes a fleet-wide manual migration
  (delete-and-recreate of user CRs, which for `DatabaseSecretClaim` deletes live Secrets). The team
  has committed to GA-level API stability without GA-level evolution machinery.
- **Alternative:** either ship `v1beta1` for the first production cycle (honest about maturity,
  conversion-free bump available later), or keep `v1` but stand up the conversion-webhook scaffold
  now and move CRD ownership out of the value-gated template path (`crds/` directory or a dedicated
  chart with `helm.sh/resource-policy: keep`). Trade-off: webhook machinery is operational surface
  the team explicitly avoided; a beta version costs marketing comfort.
- **Cost to change:** now — a version-string decision plus scaffolding; after first install — a
  cluster-by-cluster stored-object migration with user-visible deletes.
- **Symptoms already visible:** F-01 (CRD deletion cascade), F-25, F-30 (schema already too narrow).
- **Confidence:** CONFIRMED.

### A-02 Identity is a user-authored free-form map; the aggregator's own handle is received and dropped — MAJOR / CONFIRMED

- **Decision:** the classifier (`microserviceName`, `scope`, free `extraKeys`/`customKeys`) is the
  only key the operator ever holds for a database. The aggregator returns a registry `id` on
  get-by-classifier (`internal/client/types.go:221-222`) and on the changed feed (`:236-252`), and
  no CR status stores it — `InternalDatabaseStatus` holds only `trackingId`
  (`api/v1/internaldatabase_types.go:105-123`), `DatabaseSecretClaimStatus` only timestamps
  (`databasesecretclaim_types.go:49-73`).
- **Force it fails under:** the classifier must byte-match what runtime dbaas-clients independently
  construct (docs `DBaaS Operator.md:1429`, migration guide `:215-217`). When it does not, the
  failure is late and remote: a wrong `extraKeys` value provisions a *second* database and the wrong
  Secret, with `Succeeded` everywhere. Nothing ties CR ↔ aggregator record except re-computing the
  key; two `InternalDatabase` CRs resolving to one classifier silently last-writer-win (no sibling
  check exists — the classifier index is registered only for `DatabaseSecretClaim`,
  `internal/controller/suite_test.go:126`, `databasesecretclaim_controller.go:616`).
- **Consequence:** identity disputes surface as "wrong data in the Secret" or "a duplicate database
  appeared", the two most expensive symptom classes this platform can produce, and status offers no
  way to notice ("diverged from external truth" is not a representable state — §4.10).
- **Alternative:** keep the classifier as the *request* key but store the aggregator-assigned id in
  status after first resolution (`status.databaseId`), assert it on subsequent reconciles, and
  surface a `Diverged` condition on mismatch. Crossplane's `external-name` annotation and the whole
  OSB instance-id history are this exact lesson. Costs: the aggregator must firm up the currently
  best-effort `id` (`types.go:218-220`); a repair path is needed when ids legitimately change
  (restore).
- **Cost to change:** now — one additive status field and one Java guarantee; after first install —
  the same, plus backfill, so still tractable: this is the *reversible* half of the identity
  problem, which is why it is MAJOR rather than BLOCKER.
- **Symptoms already visible:** F-26 (case-mismatched `scope` accepted and stored), F-02
  (`matched == 0` fan-out indistinguishable from index drift).
- **Confidence:** CONFIRMED.

### A-03 `kubectl delete` on the database kinds is a silent no-op, and the API family is internally inconsistent about it — MAJOR / CONFIRMED

- **Decision:** `InternalDatabase`, `ExternalDatabase`, and `DatabaseAccessPolicy` carry no
  finalizer and no deregistration call; the three balancing-rule kinds *do* clean up on deletion
  (finalizers + `DELETE` endpoints), and `DatabaseSecretClaim` deletion GC-deletes its Secret.
- **Encoded in:** grep `Finalizer` over `api/v1` and `internal/` — only the binding and rule kinds;
  aggregator cleanup exists but behind a different role and consumer
  (`AggregatedDatabaseAdministrationNoNamespaceControllerV3.java:111-145`, `deleteall`,
  `@RolesAllowed(NAMESPACE_CLEANER)`); declarative configs are deleted only by the BG engine
  (`BlueGreenService.java:508`) or namespace cleanup, never by the operator.
- **Force it fails under:** GitOps prune, namespace offboarding, cluster teardown. Deleting the
  directory that created everything removes the CRs and the claimed Secrets while the databases,
  declarations, and policies live on in the aggregator indefinitely; re-creating the CRs with a
  slightly different classifier then provisions duplicates (A-02 compounding).
- **Consequence:** the CR lifetime is unrelated to the database lifetime, but nothing says so — not
  the docs (F-12: no statement found), not the status, not an event. A platform team will discover
  the semantics from an incident. Meanwhile the rule kinds teach the *opposite* lesson, so users
  cannot generalize from one kind to another.
- **Alternative:** adopt the genre's answer (Crossplane `deletionPolicy`, external-secrets
  ownership): an explicit per-CR `deletionPolicy: Orphan | Deregister` field, default `Orphan` (safe,
  matches today), a finalizer that executes the chosen policy, and a written statement per kind of
  what survives deletion. `Deregister` for `InternalDatabase` needs an aggregator endpoint that can
  mark-for-drop a single declaration+database by classifier — a boundary addition. Trade-off:
  finalizers on database kinds make namespace deletion depend on aggregator availability; the
  `Orphan` default keeps that risk opt-in.
- **Cost to change:** now — an additive spec field and one aggregator endpoint; after first install
  — the field is still additive, but changing the *default* behavior ever again is effectively
  impossible once users rely on delete-is-noop. The decision is cheap; the delay is what makes it
  expensive.
- **Symptoms already visible:** F-12.
- **Confidence:** CONFIRMED.

### A-04 Migrating a namespace between operator instances is a full teardown with a credential outage — MAJOR / CONFIRMED

- **Decision:** three individually reasonable rules compose into a trap:
  `NamespaceBinding.spec.operatorNamespace` is CEL-immutable
  (`api/v1/namespacebinding_types.go:40`); the binding cannot be deleted while any workload CR
  exists (protection finalizer, `cmd/main.go:211-238`, docs `:764-778`, `:846-853` — "Remove all
  workload resources first"); and workload identity fields are immutable, so CRs cannot be
  re-pointed (D-04, D-07).
- **Force it fails under:** §5 scenario 1 — a platform team rebalancing 300 namespaces across
  operator instances. The only compliant sequence is: delete every `DatabaseSecretClaim` (its Secret
  is GC-deleted → workloads lose mounted credentials), delete every other workload CR, delete the
  binding, recreate the binding for instance B, recreate all CRs, wait for re-materialization.
- **Consequence:** a routine shard-rebalancing operation becomes a per-namespace outage window.
  Teams will improvise (orphan the Secret by hand-editing ownerReferences, recreate CRs in a rush),
  which is exactly what an ownership model exists to prevent. There is also no documented procedure
  — the docs describe binding deletion only in the decommissioning register.
- **Alternative:** make `operatorNamespace` mutable (it is coordination state, not identity — the
  CEL freeze protects nothing the resolver cannot handle, since `SetOwner` already processes
  updates, `internal/ownership/resolver.go:86-94`), define the handover as: old instance observes
  `Foreign` and stops, new instance observes `Mine` and adopts. The Secret survives because no CR is
  deleted. Costs: a brief double-owner window during cache convergence — bounded by the watch, and
  the reconcilers are idempotent submissions, so double-reconcile is safe against the aggregator.
- **Cost to change:** now — drop one CEL rule and write the handover procedure; after first install
  — same code change, but relaxing immutability after users built tooling around delete-and-recreate
  is a behavior change to communicate.
- **Symptoms already visible:** F-11 (the Foreign→Mine transition already has no safety net —
  evidence the migration path was never designed end to end).
- **Confidence:** CONFIRMED.

### A-05 Submission without reconciliation: the declarative store has three writers and no repair loop — MAJOR / CONFIRMED

- **Decision:** `InternalDatabase`, `DatabaseAccessPolicy`, and the rule kinds submit on spec change
  only (`GenerationChangedPredicate`, `internaldatabase_controller.go:570,578`,
  `databaseaccesspolicy_controller.go:153,161`; controller-runtime's periodic resync is filtered out
  by the same predicate). Nothing ever re-reads or re-asserts aggregator state. Meanwhile the same
  `database_declarative_config` rows are written by the deprecated core-operator path
  (`DeclarativeController.java:83`) and copied/deleted by the blue-green engine
  (`BlueGreenService.java:150-153`, `:508`).
- **Force it fails under:** §5 scenarios 2 and 8. Aggregator restored from a backup: declarations
  and policies applied since the backup are gone, and no operator mechanism ever re-sends them — the
  CRs sit at `Succeeded` describing state that no longer exists, forever. BG commit: the engine
  deletes the retired namespace's declarations while `InternalDatabase` CRs still stand there at
  `Succeeded`; the CRs and the store now disagree and nothing detects it. Manual aggregator-side
  changes: same.
- **Consequence:** `status.phase: Succeeded` means "an apply succeeded once", not "the declared
  state currently holds" — a semantic gap between what the API looks like and what it is. The two
  kinds that *do* re-assert (`ExternalDatabase` every 10 min, `DatabaseSecretClaim` hourly) prove
  the model is affordable; the others simply never adopted it.
- **Alternative:** add a periodic re-apply (hours, jittered) for `InternalDatabase`,
  `DatabaseAccessPolicy`, and the rule kinds — the apply endpoint is an idempotent upsert
  (`DeclarativeDbaasCreationService.saveNewDatabaseConfig`, `:129-148`), so the cost is one POST per
  CR per period. That heals restore and BG drift without any aggregator change. Longer term, an
  origin marker on `DatabaseDeclarativeConfig` (see A-13) would let drift be *attributed*, not just
  overwritten. Trade-off: steady-state load on the aggregator, trivially bounded by the interval.
- **Cost to change:** now — a `RequeueAfter` in two controllers; after first install — the same,
  which is why this is urgent only because scenario 2 (restore) is a day-one operational reality,
  not because the fix ages badly.
- **Symptoms already visible:** F-16 shows the one level-triggered kind doing it wastefully — the
  pattern exists but was never made a policy.
- **Confidence:** CONFIRMED.

### A-06 The aggregator contract is hand-mirrored and unversioned; the skew failure mode is a fleet-wide permanent stall — MAJOR / CONFIRMED

- **Decision:** `internal/client/types.go` hand-copies the Java DTOs; there is no shared schema, no
  generated client, no contract test, and no version negotiation (`ApiVersionController.java` exists
  on the Java side and the operator never calls it). Error semantics are classified by HTTP code
  family (`types.go:296-322`), with 400/403/409/410/422 mapped to *permanent* `Stalled=True`.
- **Force it fails under:** §5 scenario 7 — the aggregator renames a field or adds a required one.
  The operator compiles, deploys, and sends yesterday's shape; the aggregator answers 400; every
  affected CR in every namespace moves to `InvalidConfiguration`/`Stalled=True` — a state defined as
  "will not retry until the spec changes" — although no user spec is wrong. Recovery after the
  aggregator (or operator) is rolled back requires touching every stalled CR or restarting the
  operator. The failure is silent until it is total.
- **Consequence:** the release train of two components in *one repository* has no guardrail: the
  monorepo is the one place where contract tests are nearly free (spin the Quarkus app or its
  OpenAPI export in CI, replay the Go client's requests), and the eight already-found semantic
  divergences (F-24…F-31) show the drift rate with the contract *frozen*. With no supported skew
  window stated, every mixed-version window during upgrade is undefined behavior.
- **Alternative:** minimum — a CI contract test: render the aggregator's OpenAPI
  (`/v3/api-docs` is already exposed) and verify the Go structs and status-code maps against it;
  declare a skew policy (N−1 aggregator supported) and check it at startup via the existing version
  endpoint, downgrading contract mismatches from `Stalled` to a loud transient. Full codegen is
  optional; the test is not. Trade-off: CI time.
- **Cost to change:** now — a CI job; after first install — the same job, plus the first silent-skew
  incident it would have prevented.
- **Symptoms already visible:** F-24, F-25, F-26, F-27, F-28, F-29, F-30, F-31 — eight divergences
  in a contract that has existed for one release.
- **Confidence:** CONFIRMED.

### A-07 Aggregator semantics live on the operator side of the boundary — MAJOR / CONFIRMED

- **Decision:** the operator compensates for gaps in the declarative API instead of the API closing
  them. The clearest case: the declarative apply stores a tenant declaration as a template and drops
  `tenantId`, so the operator itself issues the runtime get-or-create
  (`internaldatabase_controller.go:241-265`), then re-runs the *entire* apply + create every 5 s
  while the 202 lasts (`:184-188`, `:503-511`) because this endpoint has no `trackingId` to poll.
  Same family: `scope` case-folding in the controller (`:242`) where the aggregator is
  case-sensitive (F-26), and `backupDisabled` unexpressible on the wire (F-25).
- **Force it fails under:** the recurrence test. Every aggregator behavior the operator re-implements
  must now be kept semantically in lockstep across a hand-mirrored contract (A-06). Each new
  declarative gap (the next `backupDisabled`) lands as operator-side compensation code plus a CRD
  field plus a Java change — the "three layers and a Java DTO" pricing of §4.11.
- **Consequence:** the declarative API's promise ("apply this and the aggregator converges") is
  quietly false for pinned tenants, and the operator's polling shape (F-09's unbounded 5 s loop) is
  the direct architectural residue: it polls *because* the far side gives it nothing to wait on.
- **Alternative:** move materialization behind the boundary — the declarative apply for a pinned
  `tenantId` should itself materialize the database and report progress through the one async
  mechanism that already exists (`trackingId`). The operator's branch at
  `internaldatabase_controller.go:241` then deletes. Costs: a Java change and a slightly fatter
  declarative contract; buys: one owner for idempotency, retry, and terminal-failure semantics
  instead of the current split.
- **Cost to change:** now — moderate (Java + delete Go code); after first install — the same work
  plus a compatibility window in which both behaviors exist.
- **Symptoms already visible:** F-09, F-25, F-26, F-31.
- **Confidence:** CONFIRMED.

### A-08 The rotation guarantee is "at-most-once, healed within an hour", and it is promised nowhere — MAJOR / CONFIRMED

- **Decision:** rotation delivery is a leader-only poll of a shared feed with an in-memory cursor
  advanced unconditionally past failed fan-outs (`rotation_poller.go:132-144`), a best-effort
  annotation patch (`trigger.go:73-81`), and a 1 h per-claim backstop (`conditions.go:60`). The
  operator's responsibility ends at writing the Secret; nothing signals or restarts workloads, and
  no document states what an application must do or how stale a Secret may legally be.
- **Force it fails under:** the question "what can a workload rely on?". The honest answer — "your
  Secret is correct within one hour of a rotation, sooner if nothing fails; your pod must re-read
  it" — appears in no contract. Whether one hour of a possibly-invalid password is acceptable
  depends entirely on whether the aggregator/adapters keep the *old* password valid during an
  overlap window, and the operator's design neither requires nor documents such a window.
- **Consequence:** application authors design against an unstated SLO. The comparison set
  (external-secrets `refreshInterval` as a spec field, cert-manager's documented renewal window +
  ecosystem reload conventions) all make the bound and the consumer obligation explicit; here both
  are implementation constants.
- **Alternative:** (1) state the contract in the docs: staleness bound, at-most-once fan-out,
  consumer obligation to reload; (2) make the backstop a spec or values knob rather than a constant;
  (3) obtain from the aggregator team a written overlap-window guarantee, since the whole design
  leans on it. None of this changes code shape; it changes what the design *claims*. The delivery
  mechanics themselves (cursor, backstop) are defensible once the bound is a promise rather than an
  accident — the mechanical fixes (metrics, cursor-on-failure) are F-02's layer.
- **Cost to change:** now — documentation and one knob; after first install — the same, plus every
  application team that already guessed a different contract.
- **Symptoms already visible:** F-02 (silent fan-out loss), F-04/F-05 (no signal when it happens).
- **Confidence:** CONFIRMED.

### A-09 Neither the identity model nor the feed has a cluster dimension; multi-cluster is undefined — MAJOR / PLAUSIBLE

- **Decision:** the classifier's location component is `namespace` alone
  (`api/v1/common_types.go:54`); the changed feed is global across the aggregator
  (`AggregatedDatabaseAdministrationNoNamespaceControllerV3.java:209-256`) with no consumer
  identity or cluster filter; the operator's scale unit is one deployment per cluster
  (`cmd/main.go:149`, "watching all namespaces (cluster-scoped)").
- **Force it fails under:** §5 scenario 1's premise — 3 clusters, one aggregator. If two clusters
  each hold a namespace named `app-1`, their classifiers are identical and both operators (and both
  sets of runtime clients) resolve to the *same* logical database and credentials. Nothing in either
  repository states that namespace names are globally unique across clusters sharing an aggregator,
  and nothing enforces it. Separately, every cluster's poller consumes a feed most of whose entries
  belong to other clusters — harmless per-item (`matched == 0`) but it makes fan-out loss
  structurally indistinguishable from other-cluster traffic (feeds F-02's diagnosability gap).
- **Consequence:** either multi-cluster-per-aggregator is unsupported (then say so — the docs are
  silent), or namespace-name uniqueness is a hard platform invariant that lives only in someone's
  head.
- **Alternative:** decide and write it down. If multi-cluster is real: an aggregator-side cluster/
  origin dimension on registration and on the feed (large, boundary-level), or a documented
  platform invariant of globally unique namespace names (free, but must be enforced by the
  namespace-provisioning tooling). If not real: one sentence in the docs.
- **Cost to change:** now — a sentence or a design decision; after first install — a data-model
  migration inside the aggregator.
- **Symptoms already visible:** none yet — this is the class of finding that has produced no symptom
  because no one has run the topology.
- **Confidence:** PLAUSIBLE — rests on the assumption that one aggregator serving several clusters
  is an intended topology (the feed's docstring "Consumed by the dbaas-operator rotation poller" and
  the per-cluster HA machinery suggest it; no document confirms or denies it).

### A-10 One usable database is two CRs whose classifiers must agree by hand — MINOR / CONFIRMED

- **Decision:** provisioning (`InternalDatabase`) and credential materialization
  (`DatabaseSecretClaim`) are separate kinds joined only by structurally equal classifiers; there is
  no reference between them and no cross-validation (the claim's controller never looks at
  `InternalDatabase` objects — no such watch or index exists in
  `databasesecretclaim_controller.go`).
- **Force it fails under:** everyday authoring. A typo in one of the two classifier copies produces
  a claim waiting forever on `DatabaseNotFound` next to a `Succeeded` database CR — §5 scenario 6's
  "will never match" case, distinguishable from slow provisioning only by the 10 min timeout
  heuristic (`conditions.go:48`).
- **Consequence:** the aggregator's internal decomposition (declarations vs connections) leaks into
  the user-facing API. The split itself is defensible — cross-service claims (CDC consumers) and
  role-variant claims genuinely need claims without a database CR — but the *unlinked* variant
  forces every first-party user to hand-maintain identity in two places.
- **Alternative:** an optional `spec.databaseRef` on the claim (name of an `InternalDatabase` in the
  namespace) from which the controller derives the classifier, keeping the raw-classifier form for
  cross-service cases. Additive; costs one watch and one derivation path.
- **Cost to change:** now — additive; after first install — additive still.
- **Symptoms already visible:** none directly; the F-02/F-05 diagnosability gaps make the mismatch
  case harder to see when it happens.
- **Confidence:** CONFIRMED.

### A-11 "Silently not mine" is a designed state: unowned CRs get no status, ever — MINOR / CONFIRMED

- **Decision:** `checkOwnership` returns before the status-patch defer is installed
  (`internal/controller/helpers.go:143-162` — callers return prior to `patchStatusOnExit`), so a CR
  in an unbound or foreign namespace holds an empty status indefinitely; metrics exclude it too
  (`resource_metrics.go:385-390`, per F-05).
- **Force it fails under:** the API-machinery convention that an accepted object is eventually
  acknowledged by *some* controller. Here the most common onboarding error (missing binding)
  produces the same observable as "operator down".
- **Consequence:** architectural, not cosmetic: the ownership model's cheapest legitimacy feature —
  telling the object who ignored it and why — was traded away to keep foreign instances from
  touching status. For `Foreign` that restraint is correct (two writers would fight); for `Unbound`
  no other writer exists, and the restraint protects nothing.
- **Alternative:** in the `Unbound` branch only, write `Ready=False / Reason=NamespaceNotBound`
  (single writer, no fight possible); keep `Foreign` silent but document it. Cheap; the trade-off
  (status writes for permanently unbound namespaces every 5 min requeue) is bounded by the existing
  `ownershipUnboundRetryInterval` (`conditions.go:40`).
- **Cost to change:** now or later — identical and small; MINOR because it compounds rather than
  breaks.
- **Symptoms already visible:** F-05 (including the documentation actively mis-explaining the empty
  phase).
- **Confidence:** CONFIRMED.

### A-12 `PermanentBalancingRule` is a cluster-scoped concern wearing a namespaced kind — MINOR / CONFIRMED

- **Decision:** a rule that targets arbitrary business namespaces cluster-wide is modeled as a
  namespaced CR whose authority comes from *location* (informer scoped to the operator's own
  namespace, `cmd/main.go:161-170`) instead of from RBAC on a cluster-scoped kind; it is exempt from
  the ownership gate and from binding protection (`cmd/main.go:217-219`).
- **Force it fails under:** two operator instances in different namespaces, each with its own
  `PermanentBalancingRule` set targeting overlapping namespaces: both PUT/DELETE against the same
  global aggregator rule set with no arbitration (adversarial review open question 5 — still open).
  Also the day someone asks for `kubectl get` visibility of "the cluster's permanent rules": they
  live in whichever namespace the operator happens to run in.
- **Consequence:** scope-by-location is invisible in the API surface — the CRD says "Namespaced" and
  only a doc footnote (`DBaaS Operator.md:91`, `:124-125`) explains the truth.
- **Alternative:** model it cluster-scoped (matches its semantics, RBAC-gates writers properly), or
  keep it namespaced but add the same instance-arbitration the other kinds get. Cluster-scoping a
  shipped kind is a breaking change — which is why this is worth deciding *before* first install and
  is only friction after.
- **Cost to change:** now — a scope flag on a not-yet-installed CRD; after first install — a new
  kind plus deprecation of the old one.
- **Symptoms already visible:** none; the three-schema question (§4.1) is otherwise sound — the
  three rule kinds map to three genuinely different aggregator endpoints and lifecycles.
- **Confidence:** CONFIRMED.

### A-13 Coexistence with core-operator is last-writer-wins on a shared store, with no origin marker and no cutover doctrine — ACCEPTED-DEBT / CONFIRMED

- **Decision:** both operators write the same `database_declarative_config` rows through the same
  service (`DeclarativeController.java:83` — deprecated path; `ConfigControllerV1.java:67-103` — new
  path; upsert keyed by classifier+namespace in `DeclarativeDbaasCreationService.java:129-148`); the
  entity records no owner (`DatabaseDeclarativeConfig.java:15-87` — no origin field); the migration
  guide ends at "Apply, then verify" (`migrate-declarations-from-core-operator.md:241-257`) with no
  cutover, disable, or rollback step (F-20).
- **Force it fails under:** the intended migration itself. A converted-but-not-deleted legacy
  declaration leaves two controllers permanently re-asserting payloads for one classifier; if the
  conversion introduced any difference, the store flip-flops with each side's next reconcile, and
  nothing in either system can attribute a given row to a writer.
- **Consequence:** probably a defensible bridge — the payloads are supposed to be identical, upserts
  are idempotent, and the window is meant to be short. But "meant to be short" is exactly the kind
  of constraint that must be written down: which operator is authoritative during overlap, when to
  disable core-operator, and what the rollback path is once workloads depend on claim-managed
  Secrets (the guide is silent on all three).
- **Alternative:** minimum — finish the migration guide (cutover order, verification, rollback).
  Better — an `origin` column on the declarative config, letting the aggregator log or reject
  cross-writer overwrites during the window. Trade-off: a Flyway migration for a transitional
  feature.
- **Cost to change:** now — a doc section; after first install — the same doc plus whatever the
  first flip-flop incident costs.
- **Symptoms already visible:** F-20.
- **Confidence:** CONFIRMED.

### A-14 Active-passive singleton with no bulkheads is the intended (and undocumented) availability model — ACCEPTED-DEBT / CONFIRMED

- **Decision:** one leader does all reconciling and polling; standbys idle
  (`rotation_poller.go:74`; leader-gated controllers); one shared rate limiter
  (`cmd/main.go:197-201`), one HTTP client with a fixed 30 s timeout and no retries
  (`aggregator_client.go:36,108`), no prioritization between "first credential" and "rule resync".
  Meanwhile the chart ships an HPA scaling to 5 replicas (`resource-profiles/prod.yaml:14-15`) that
  can only add idle standbys — the chart and the code disagree about what a replica is *for*.
- **Force it fails under:** an aggregator outage plus a large CR population, where every kind shares
  one queue's fate (F-03 measured the cross-kind interference); and any reader of the chart, who
  reasonably concludes the operator scales horizontally.
- **Consequence:** active-passive is the right call for this workload — the state is small, the
  work is I/O-bound submission, and correctness leans on single-writer invariants (poller cursor,
  status writes). What is missing is the design saying so: an availability budget ("operator down
  N hours costs: rotations delayed to startup-reconcile, provisioning paused, nothing lost") and a
  chart that matches (fixed 2 replicas, no HPA).
- **Alternative:** document the model and the outage budget; delete the HPA (F-18 owns the
  mechanics); keep bulkhead work (per-controller limiters — F-03) at the code layer where it
  belongs.
- **Cost to change:** unchanged over time; listed so the next reviewer stops re-litigating
  active-passive.
- **Symptoms already visible:** F-03, F-18, F-19.
- **Confidence:** CONFIRMED.

## Pressure-test table

| # | Scenario | What the architecture makes you do | Absorbed? | Cost |
| --- | --- | --- | --- | --- |
| 1 | 300 ns / 3 clusters; migrate a namespace A→B | Delete every workload CR (Secrets GC'd), delete binding, recreate everything under B (A-04); trust namespace-name uniqueness across clusters (A-09) | **No** | Per-namespace credential outage; undefined identity across clusters |
| 2 | Aggregator restored from 1 h-old backup | `DatabaseSecretClaim` re-fetches within ≤1 h (safety net) and rewrites Secrets to restored credentials; `ExternalDatabase` re-registers in ≤10 min; declarations/policies applied in the lost hour are **never** re-sent (A-05) | Partially | Silent CR/store divergence until a spec change; credential correctness depends on adapter-side restore semantics |
| 3 | Cluster restore / GitOps re-apply onto live aggregator | Re-apply is an idempotent upsert; claims re-resolve and re-materialize Secrets; binding must land first (5 min unbound safety net covers ordering) | **Yes** | Minutes of convergence delay |
| 4 | Tenant offboarded: `kubectl delete namespace` | Namespace GC deletes CRs (binding finalizer resolves once workloads go); databases, declarations, and policies survive in the aggregator until someone with `NAMESPACE_CLEANER` calls `deleteall` (A-03) | Partially | Unbounded aggregator garbage; cleanup is an out-of-band tool nobody in this repo owns |
| 5 | Rotation during leader roll | New leader seeds cursor at high-water mark before draining startup reconciles; startup reconcile re-fetches all claims | **Yes** | Startup reconcile load; verified ordering (adversarial review) |
| 6 | Claim that never matches vs DB that appears in 2 h | Both retry at 5 s; after 10 min the never-match case gains `DatabaseNotFoundTimeout` — a heuristic, not a diagnosis; no cross-check against sibling `InternalDatabase` (A-10) | Partially | Operator time; misdiagnosis risk |
| 7 | Aggregator renames/adds a required field | Nothing warns: old-shape request → 400 → fleet-wide `Stalled=True` requiring per-CR touches to recover (A-06) | **No** | Cluster-wide manual recovery; undefined mixed-version window |
| 8 | Blue-green promotion with `InternalDatabase` CRs in both namespaces | BG engine copies then deletes declarations server-side; CRs in the retired namespace keep `Succeeded` over a deleted declaration; no reconciler notices (A-05, D-19) | **No** | Silent divergence; next spec edit resurrects a retired declaration |
| 9 | Two CRs resolve to one classifier in one namespace | `DatabaseSecretClaim`: designed older-wins arbitration. `InternalDatabase`: silent last-writer-wins upsert, both `Succeeded` (A-02) | Partially | Flip-flopping declaration with no signal |
| 10 | Operator down 4 h during rotation + provisioning wave | Nothing rotates or provisions until restart; startup reconcile then heals claims and resumes polls; workloads run on stale credentials for the outage + validity of old password (A-08) | Partially | Bounded only by the *undocumented* overlap window; budget stated nowhere (A-14) |

## Sound by design

Attacked and not faulted — do not re-litigate:

- **Single-binding arbitration.** One `NamespaceBinding` per namespace with the name CEL-pinned to
  `binding` (`namespacebinding_types.go:63`) makes double-claim impossible by etcd uniqueness, not
  by luck. The sharding *primitive* is sound; only the migration path (A-04) is not.
- **Outbound-only posture.** No inbound endpoint, rotation by poll: the right shape for the
  security topology, and the cursor-seed-before-drain ordering is correct (verified in the
  adversarial review).
- **Role-agnostic rotation fan-out.** Waking every claim on `(classifier, type)` and letting the
  aggregator's `DatabaseRolesService` resolve effective roles (docs `:2197-2245`) is the correct
  refusal to duplicate authority — the best boundary decision in the codebase.
- **Content-aware Secret writes.** No-op writes are suppressed, so kubelet reload churn tracks real
  rotations only (docs `:2149-2155`).
- **Secret handling.** No informer, no cluster-wide `secrets` RBAC, ownerReference GC, per-namespace
  opt-in Role (`cmd/main.go:180-187`, docs `:595-617`): least privilege with an explicit OOM
  rationale.
- **Sibling `secretName` arbitration.** Older-wins with UID tiebreak and watch-driven recovery is
  genre-conformant (cert-manager) and was verified convergent on both peers.
- **Classifier canonicalization.** Within its premise (A-02), the flatten/index/round-trip machinery
  including `json.Number` precision is closed and tested.
- **`ExternalDatabase` resync.** The one kind that treats the aggregator as drift-prone and repairs
  it periodically — the model A-05 asks the others to adopt.
- **Status vocabulary.** `Ready`/`Stalled` with documented reason constants and
  `observedGeneration` discipline; all 23 reasons documented (adversarial review confirmed full
  coverage).

## Questions for the architect

1. Is "deleting an `InternalDatabase`/`ExternalDatabase`/`DatabaseAccessPolicy` leaves the
   aggregator untouched" the intended permanent contract, or a gap? — resolves A-03's alternative
   (deletionPolicy vs documentation-only).
2. Is one aggregator serving several Kubernetes clusters a supported topology, and if so, what
   guarantees that namespace names never collide across those clusters? — resolves A-09's
   confidence.
3. What operator↔aggregator version skew is supported during an upgrade, and in which order do the
   two components roll? — scopes A-06's contract test and startup check.
4. Is moving a bound namespace to another operator instance a supported day-2 operation? If yes,
   the CEL freeze on `NamespaceBinding.spec.operatorNamespace` contradicts it — resolves A-04.
5. After a credential rotation, for how long does the previous password remain valid at the
   adapter? — turns A-08's staleness bound into a real SLO or a real problem.
6. Should the declarative apply itself materialize a pinned-tenant database (and report it via
   `trackingId`), retiring the operator-side get-or-create loop? — resolves A-07.
7. Is `restrictedEnvironment`'s implied namespace-scoped mode a roadmap item or dead configuration
   (F-08)? — decides whether the scale unit (§4.8) has a second intended answer.
8. When blue-green commit deletes the retired namespace's declarations, what is supposed to happen
   to the `InternalDatabase` CRs still standing there? — resolves the scenario 8 row and part of
   A-05.
