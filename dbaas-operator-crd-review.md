# DBaaS operator CRD API review

Review of the eight custom resources in the `dbaas.netcracker.com/v1` group: kind names, field names, and schema
design. Every finding below survived an adversarial verification pass against the operator source, the aggregator
source, and the project's own docs.

Reviewed at `origin/feat/operator-dev`, whose `api/v1` and `config/crd` trees are identical to `main`.

## Summary

The status machinery follows Kubernetes conventions closely: `metav1.Condition` with `Ready` and `Stalled`,
`+listType=map`, `observedGeneration`, an unconstrained `phase` with a written rationale, CEL immutability rules,
`categories=dbaas`, and print columns on every kind.

The naming is in better shape than a first pass suggests. Nearly every field name that reads as unconventional is
the aggregator's own spelling, carried through deliberately; the departures from it are for safety or to remove an
untyped config map. One kind-name pair is worth a discussion. The remaining findings are validation gaps and one
genuine trap.

## Confirmed defects

### 1. `initialInstantiation: {}` silently selects clone mode

The highest-value finding, verified end to end.

`DatabaseDeclaration.InitialInstantiation.approach` is initialized to `"clone"` in the aggregator
(`dbaas/.../dto/declarative/DatabaseDeclaration.java:56`). The Go side sends the object whenever
`spec.initialInstantiation != nil`, and `approach` carries `json:"approach,omitempty"`
(`api/v1/internaldatabase_types.go:37`, `internal/client/types.go:198`), so a CR with `initialInstantiation: {}`
transmits `{"initialInstantiation": {}}` — key present, `approach` absent — and the aggregator applies its field
initializer.

The operator's own guards do not fire, because both compare `Approach == "clone"` exactly
(`internal/controller/internaldatabase_controller.go:376-386`). With `lazy: true` the request reaches
`DeclarativeDbaasCreationService.java:139` and throws `UnsupportedOperationException("lazy creation is prohibited
in blue-green mode")` — the exact failure the operator check exists to prevent.

Fix: make `approach` required inside `initialInstantiation`, or normalize the empty value to `new` before
serialization, or add a CEL rule tying `approach=clone` to a present `sourceClassifier`.

### 2. `scope` case handling diverges between operator and aggregator

The operator compares the scope with `strings.EqualFold` (`internal/controller/internaldatabase_controller.go:242`);
the aggregator uses `Objects.equals` (`AggregatedDatabaseAdministrationService.java:487-491`).

With `scope: Tenant`, the declarative apply succeeds (it validates only that the `scope` key exists), the operator
then takes the tenant-materialization path, and the `PUT /api/v3/dbaas/{ns}/databases` call is rejected with
`InvalidClassifierException`. Elsewhere the aggregator silently treats `Tenant` as non-tenant
(`DatabaseDeclarativeConfig.java:82`), which is the quieter half of the same bug.

Fix belongs in the controller: match the aggregator's case sensitivity. An OpenAPI enum on `scope` is the tempting
alternative and the wrong one — it pins the CRD schema to a value set owned by a separately released service, the
same hazard `AGENTS.md` records for the `phase` field.

### 3. Doc comments reference a Java type that does not exist

`api/v1/internaldatabase_types.go:22, 31, 54` attribute the schema to an `InternalDatabase` Java DTO
("Mirrors InternalDatabase.VersioningConfig in the aggregator", "Field names and semantics match the
InternalDatabase Java DTO"). No such class exists; it is `DatabaseDeclaration`, with nested `VersioningConfig` and
`InitialInstantiation`. Four more stale references live in `internal/client/types.go:161, 171, 184, 189` — seven
sites in two files. A reader or an agent following the reference searches for something that is not there.

### 4. No size limits on any project-owned field

The three `maxLength` values in each generated CRD (`32768`, `1024`, `316`) all come from `metav1.Condition`.
`maxItems` count is zero across all eight CRDs. `customKeys` and `extraKeys` are unstructured JSON with
`x-kubernetes-preserve-unknown-fields`, and `spec.rules` carries `MinItems=1` with no upper bound. This is an
etcd-size exposure now and a CEL cost-budget problem as soon as a rule is added over those lists.

### 5. Deletion semantics are absent from the API

`NamespaceBinding` and the three balancing rules use finalizers. `InternalDatabase`, `ExternalDatabase`, and
`DatabaseAccessPolicy` do not, so deleting the CR neither unregisters nor drops the database. The behavior is
defensible; the gap is that nothing in the API states it — no `deletionPolicy` field, and no sentence in the kind's
doc comment. A user who deletes an `InternalDatabase` cannot learn from the schema that the database survives.

### 6. Cross-field validation runs in the controller instead of at admission

Two rules produce an `InvalidConfiguration` status where they could reject `kubectl apply`:

- `lazy=true` with `initialInstantiation.approach=clone` (`internal/controller/internaldatabase_controller.go:376`).
- "at least one of `services` or `policy`" (`internal/controller/databaseaccesspolicy_controller.go:99`).

Both are single-`spec` rules that CEL can express, and the project already places `XValidation` on struct and root
types. Each needs `has()` guards the naive version misses: `lazy` has no `+kubebuilder:default`, so a bare
`self.lazy` errors on an absent field, and `has(self.services)` is true for an explicitly empty list, so the rule
must compare `size()`.

The documented CEL limitation (a root-level rule sees only `metadata.name` and `metadata.generateName`) applies to
the label and namespace checks on `DatabaseSecretClaim`, not to these two.

### 7. `spec.settings` narrows the backend contract

The aggregator types `settings` as `Map<String, Object>` (`DatabaseDeclaration.java:27`); the CRD narrows it to
`map[string]string` (`api/v1/internaldatabase_types.go:87`), so numeric, boolean, and nested adapter settings
cannot be expressed. The operator itself reads the wider shape elsewhere
(`internal/controller/databasesecretclaim_controller.go:533`). The narrowing is documented as intentional
("Free-form string-to-string map of adapter-specific settings", `docs/howto/DBaaS Operator.md:1438`), so this is a
question to confirm rather than a defect to fix: does any adapter need a non-string setting?

### 8. Finalizer and annotation domains disagree

Annotations use `dbaas.netcracker.com/...` (`api/v1/annotations.go:29, 45`); finalizers use
`platform.dbaas.netcracker.com/...` (`balancingrule_types.go:36, 40, 44`, `namespacebinding_types.go:30`). Both are
valid. Cosmetic, listed for completeness.

## Field names mirror the aggregator, and that is the right default

Almost every scalar field in these CRDs carries the aggregator's own name, verbatim:

| CR field | Aggregator source |
|---|---|
| `type` — `InternalDatabase`, `ExternalDatabase`, `PolicyRole`, and both business-namespace rule items | `DatabaseDeclaration.type`, `ExternalDatabaseRequestV3.type`, `PolicyRole.type`, `OnMicroserviceRuleRequest.type`, `RuleRegistrationRequest.type` |
| `dbType` — `PermanentBalancingRuleItem` | `PermanentPerNamespaceRuleDTO.dbType` |
| `dbName` | `ExternalDatabaseRequestV3.dbName` |
| `additionalRole`, `defaultRole` | `PolicyRole.additionalRole`, `.defaultRole` |
| `services`, `policy`, `disableGlobalPermissions` | `RolesRegistration` |
| `lazy`, `settings`, `namePrefix`, `versioningConfig`, `initialInstantiation`, `approach`, `sourceClassifier` | `DatabaseDeclaration` |
| `label`, `order`, `microservices`, `physicalDatabaseId`, `namespaces` | `RuleOnMicroservice`, `RuleRegistrationRequest`, `PermanentPerNamespaceRuleDTO` |
| `classifier`, `microserviceName`, `scope`, `tenantId`, `customKeys`, `userRole` | classifier and connection-lookup payloads |

Two consequences follow.

**`dbType` versus `type` is not an operator defect.** The inconsistency lives in the aggregator API — permanent rules
say `dbType`, the other two rule endpoints say `type` — and the CRDs reflect it faithfully. Renaming on the CR side
would introduce a translation layer in the controller and make the CR stop matching the payload it produces. If the
spelling is worth unifying, the place to do it is the aggregator, and the CR follows.

The same reasoning retires several rename candidates that look attractive in isolation: `additionalRole` reads odd
for a list, `dbName` is an abbreviation next to the fully spelled `microserviceName`, and `label` holds a `key=value`
string where a structured pair would be cleaner. Each is the aggregator's own spelling, and each is also a field
users copy verbatim when migrating off Core Operator.

**Where the CRDs do depart from the wire, the reason is substantive, not stylistic.** `connectionProperties` is a
structured type with `credentialsSecretRef` rather than the aggregator's `List<Map<String, Object>>`, so credentials
come from a Secret instead of being inlined into a readable CR. `NamespaceBalancingRuleItem{name, type,
physicalDatabaseId, order}` flattens `RuleRegistrationRequest{order, type, rule{type, config}}` plus the rule name
from the URL path, replacing an opaque config map with a named field. `SecretKeyMapping`, `secretName`,
`operatorNamespace`, and the singleton `spec.rules[]` wrappers have no wire counterpart at all.

That is a coherent policy — mirror the aggregator for scalars, depart only for safety or to remove a bag-of-values
field — and it is worth writing into `AGENTS.md`. It is not recorded anywhere today, so a reviewer (or an agent)
reading the CRDs alone sees the mirrored names as carelessness and proposes to "fix" them.

## Naming: one pair worth discussing

`InternalDatabase` and `ExternalDatabase` do not say what distinguishes them. The real difference is that one orders
provisioning and the other registers a database that already exists, and "internal" carries neither meaning on its
own.

Two things weaken this into a discussion rather than a defect:

- Internal-versus-external is the aggregator's own vocabulary, not a coinage. There is no DTO by that name, but the
  persistence layer speaks it — `findInternalDatabaseByNamespace`, `saveInternalDatabase`, and
  `findAllInternalDatabases` filtering on `!database.isExternallyManageable()`
  (`DatabaseRegistryDbaasRepositoryImpl.java:236-244`) — and a user-facing runbook is titled
  *Register logical database as internal*.
- The obvious replacements are each worse. `ManagedDatabase` sits beside an aggregator concept spelled
  `externally_manageable` and reads as its opposite. `DatabaseRegistration` collides with `DatabaseRegistry`, the
  aggregator entity covering both kinds. `DatabaseClaim` lands next to `DatabaseSecretClaim`, giving the group two
  `Claim` kinds with different binding targets.

If the pair is renamed, the replacement has to clear all three collisions. Absent a candidate that does, the
cheaper fix is a doc-comment line on each kind stating the distinction in one sentence.

## Compatibility note

All eight kinds are served and stored at `v1` with no alpha or beta stage. That makes several otherwise-attractive
changes breaking, not cosmetic:

| Change | Breaking? |
|---|---|
| Renaming any spec field | Yes — existing CRs stop validating, the removed field is pruned on write, and the name stops matching the aggregator payload |
| Changing a field's type | Yes |
| Adding an OpenAPI enum to a shipped field | Yes — an existing object with an out-of-set value is rejected on its next update |
| Renaming a kind | Yes — needs a conversion webhook |
| Adding `maxItems` / `maxLength` | Yes for any object already over the limit |
| Adding a CEL rule | Only for objects that already violate it |
| Fixing the `EqualFold` comparison | No |
| Fixing doc comments | No |

## Recommended order of work

1. **Fix the `initialInstantiation: {}` trap** — a wrong-mode provisioning path reachable from a valid-looking CR.
2. **Fix the `EqualFold` scope comparison** in the controller to match the aggregator.
3. **Correct the seven stale `InternalDatabase` DTO references** in `api/v1` and `internal/client`.
4. **State the deletion semantics** in the doc comments of `InternalDatabase` and `ExternalDatabase`, or add
   `deletionPolicy`.
5. **Add `maxItems` / `maxLength`** to project-owned collections and strings, sized above any real object.
6. **Move the two cross-field checks into CEL**, with the `has()` guards noted above.
7. **Record the mirroring policy in `AGENTS.md`** — scalars carry the aggregator's names verbatim; departures are
   for safety or to remove a bag-of-values field. Cheap, and it stops the next reviewer from proposing renames that
   would break the payload.
8. **Confirm whether any adapter needs a non-string `setting`** before treating item 7 as a defect. If the spelling
   of `dbType` versus `type` is worth unifying, raise it against the aggregator API, not the CRDs.
