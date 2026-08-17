# NamespaceBinding removal handoff

This document records the complete implementation of the NamespaceBinding removal on branch
`feat/removing_NamespaceBinding`. It is intended to let another engineer or AI agent understand,
validate, and continue the work without reconstructing the design from the diff.

## Objective

Remove the per-namespace `NamespaceBinding` custom resource. Customers should assign each managed
DBaaS custom resource directly to an operator instance through `spec.operatorNamespace`, instead of
creating one extra binding object in every workload namespace.

The final managed kinds are:

1. `ExternalDatabase`
1. `InternalDatabase`
1. `DatabaseSecretClaim`
1. `DatabaseAccessPolicy`
1. `MicroserviceBalancingRule`
1. `NamespaceBalancingRule`
1. `PermanentBalancingRule`

## Final runtime contract

Every managed kind has a required, non-empty, immutable `spec.operatorNamespace`. The value is the
namespace in which the intended dbaas-operator instance runs; the operator receives the same value
through `CLOUD_NAMESPACE`.

Each reconciler fetches the CR and calls `isEligibleForOperator` before status, finalizer, Secret,
or aggregator mutations. A CR is managed only when:

```text
spec.operatorNamespace == CLOUD_NAMESPACE
```

A CR assigned to another operator is ignored. This is intentional and prevents two cluster-wide
operator instances from managing the same object.

`PermanentBalancingRule` has one additional rule:

```text
metadata.namespace == spec.operatorNamespace
```

This keeps the permanent-rule singleton in the namespace of its assigned operator while allowing
one such CR for each operator instance in the cluster. The other six kinds may remain in workload
namespaces.

## API and generated artifacts

The API type definitions add the field and CEL immutability validation to all seven CR specs.
`NamespaceBinding` types and generated deepcopy code were removed.

Generated surfaces were refreshed:

- `config/crd/bases` contains seven managed CRDs and no NamespaceBinding CRD;
- Helm CRD templates mirror those generated CRDs;
- `config/crd/kustomization.yaml` no longer references NamespaceBinding;
- generated RBAC and Helm RBAC no longer grant NamespaceBinding permissions;
- `PROJECT` no longer registers NamespaceBinding;
- `make manifests` removes old generated CRD YAML before regeneration so deleted API kinds do not
  leave stale files behind;
- samples and development resources contain the required operator assignment.

Do not edit the generated CRDs, Helm CRD templates, RBAC output, or deepcopy file manually. If an API
or RBAC marker changes, run:

```bash
make manifests
make generate
make sync-helm-crds
```

## Controller and runtime changes

The NamespaceBinding controller, tests, finalizer handling, and `internal/ownership` cache/resolver
were removed. The other reconcilers no longer watch NamespaceBinding events. They filter directly on
their own `spec.operatorNamespace` through `isEligibleForOperator`.

`cmd/main.go` passes `CLOUD_NAMESPACE` into every reconciler as `MyNamespace`. The
`PermanentBalancingRule` controller is now watched cluster-wide like the other CRs and applies its
extra namespace validation during reconciliation.

The rotation poller filters `DatabaseSecretClaim` objects by the operator assignment before stamping
rotation annotations. Resource-state metrics use the same per-CR assignment filter.

The old NamespaceBinding metrics, reconcile-trigger value, events, conditions, dashboard panel, and
documentation were removed. Monitoring documentation and the Grafana dashboard were updated in the
same change.

## Safe upgrade from an existing installation

Installing the final chart directly over an installation that still has NamespaceBindings is not a
safe migration. Existing CRs first need the new field, and binding finalizers must be released before
the NamespaceBinding CRD is removed.

Use `hack/migrate_namespace_bindings.py` with an explicit kubeconfig context:

```bash
python3 hack/migrate_namespace_bindings.py --context <context>
python3 hack/migrate_namespace_bindings.py --context <context> --execute
```

The first command is read-only. The execute path performs these operations in order:

1. Apply the seven migration-compatible generated CRDs.
1. Read each existing NamespaceBinding assignment.
1. Patch missing `spec.operatorNamespace` values on the six workload-scoped kinds.
1. Assign an existing `PermanentBalancingRule` from its own `metadata.namespace`.
1. Verify every managed CR has a valid assignment.
1. Delete NamespaceBindings and remove only
   `platform.dbaas.netcracker.com/binding-protection` when it remains.
1. Confirm the bindings are gone before allowing the Helm upgrade to proceed.

The script stops on conflicting assignments, missing assignments, invalid permanent-rule placement,
authorization failures, network failures, API timeouts, or bindings that remain blocked by other
finalizers. `--ignore-not-found` is used only with checked `kubectl` calls, so infrastructure errors
cannot be mistaken for successful deletion.

The migration procedure is documented for users in `docs/howto/DBaaS Operator.md` and linked from
the repository README.

## Declaration and mounted-secret migration packages

The Core Operator declaration converter now requires `--operator-namespace` and emits the value into
generated `InternalDatabase` and `DatabaseAccessPolicy` specs. Its skill documentation, examples,
mapping reference, README, and tests were updated. The package version is `2.0.0` because the new CLI
argument is required.

The mounted-secret migration skill also generates `InternalDatabase` and `DatabaseSecretClaim`, so
its canonical contracts now include the field. It must independently resolve and record the actual
operator namespace; it must not assume `dbaas-system` or default to the workload namespace. The
templates use `<operator-namespace>`, and the workflow requires exact-value validation on every
generated managed CR.

## Monorepo consumers outside dbaas-operator

The following sibling surfaces were updated because they create or apply these CRs:

- `dbaas/dbaas-integration-tests/.../OperatorHelper.java` adds the operator namespace to builders for
  all seven managed kinds and removes the NamespaceBinding builder and CRD context;
- `OperatorIT.java` removes NamespaceBinding setup, cleanup, and NamespaceBinding-specific tests while
  retaining the required namespaced Secret Role and RoleBinding;
- Go, Spring, and Quarkus sample-service Helm templates add the field to every `InternalDatabase` and
  `DatabaseSecretClaim`;
- the sample-service environment intentionally uses the test namespace for both the operator and the
  workloads, matching how that workflow deploys dbaas-operator;
- `test-apps/dev/namespacebinding.yaml` was deleted;
- `.github/workflows/integration-tests-sample-service.yml` no longer applies that deleted file or
  waits for the old binding finalizer; it still applies `test-apps/dev/secret-rbac.yaml`.

There should be no tracked NamespaceBinding producer or workflow reference outside the operator after
this change.

## Test and validation evidence

Completed successfully:

- `make test` for the Go operator, including controller envtest coverage;
- controller package coverage reported as 83.0 percent during that run;
- six unit tests for `hack/migrate_namespace_bindings.py`;
- four declaration-converter tests;
- Python byte-compilation for both migration scripts;
- `make sync-helm-crds`, which synchronized seven Helm CRD templates;
- Helm rendering of the Go, Spring, and Quarkus sample-service charts with
  `operatorNamespace: test-ns` in every generated DBaaS CR;
- YAML parsing of `.github/workflows/integration-tests-sample-service.yml`;
- a read-only migration dry run against context `kind-dbaas`;
- `git diff --check`.

The standalone Java integration-test compile was attempted with:

```bash
mvn -f dbaas/dbaas-integration-tests/pom.xml -DskipTests test-compile
```

It stopped during dependency resolution because private GitHub Packages returned `401 Unauthorized`
for `cloud-core-extension` and `core-error-handling-rest`. Compilation did not begin, so an
authenticated CI run must provide the final Java compile result.

The full disposable-cluster E2E suite should still be run in CI or an isolated Kind cluster before
release. Do not run it against a shared development or production cluster.

## Automatic review history

The locally cloned `qubership-ai-packages` `codex-review` skill was run in automatic mode over an
isolated worktree. It completed three iterations, the configured maximum.

Findings fixed across the iterations:

1. Added safe NamespaceBinding finalizer retirement before CRD removal.
1. Added staged CRD installation before patching existing resources.
1. Added operator assignment to the declaration converter.
1. Removed the stale NamespaceBinding entry from `PROJECT`.
1. Updated monorepo CR producers and removed obsolete binding tests and fixtures.
1. Corrected migration examples to use a dedicated operator namespace.
1. Made `kubectl` failures distinct from confirmed NotFound results.
1. Bumped the declaration-migration package for its required argument.
1. Removed the mounted-secret workflow's hardcoded `dbaas-system` assumption.

The ninth finding was returned by iteration 3 and fixed afterward. The review workflow stops at three
iterations, so that narrow documentation fix did not receive a fourth external Codex pass. Its final
Markdown was checked locally for line length and `git diff --check` passed.

## Remaining handoff checks

Before merging or releasing:

1. Run the Java integration-test compile with authenticated GitHub Packages access.
1. Run normal CI, including generated-artifact verification.
1. Run the isolated Kind/sample-service E2E workflow.
1. Test the migration utility on an upgrade fixture that contains legacy CRs and at least one
   NamespaceBinding with the protection finalizer.
1. Ensure release notes require the staged migration before installing the final chart.

## Worktree hygiene

This repository may contain unrelated untracked development documents, monitoring fixtures, local
scripts, and service experiments. They are not part of the NamespaceBinding-removal commit. Stage the
feature paths selectively and inspect `git diff --cached` rather than using `git add -A`.
