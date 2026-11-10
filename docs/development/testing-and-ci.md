# Testing and CI Guidelines

## Test Levels

| Level       | Where                           | Tools                                                                      |
|-------------|---------------------------------|----------------------------------------------------------------------------|
| Unit        | `dbaas-aggregator/src/test`     | JUnit, Mockito                                                             |
| Component   | `dbaas-aggregator/src/test`     | `@QuarkusTest`, Testcontainers PostgreSQL, `quarkus-wiremock` for adapters |
| Operator    | next to the Go sources          | Go `testing`, Ginkgo and Gomega, envtest                                   |
| Integration | `dbaas/dbaas-integration-tests` | JUnit and OkHttp against a deployed cluster                                |

New `service/` logic and new operator reconcile paths need unit tests.

```bash
mvn test -pl dbaas/dbaas-aggregator -Dtest=<ClassName>
make -C dbaas-operator test-unit
```

## Unit and Component Tests

- Name test classes `<Class>Test` and methods `test<MainTestedLogic>_<WhatAndUnderWhatCondition>`, for example
  `testMarkAsOrphan_shouldBeMarkedForDrop`.
- Build `Database` and `DatabaseRegistry` fixtures with `com.netcracker.cloud.dbaas.utils.DatabaseBuilder` and its
  constants (`PG_TYPE`, `TEST_NS`), not by setting entity fields by hand.
- Use `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`.

## Integration Tests

- Name classes `<Feature>IT` and extend `AbstractIT`, which port-forwards to the aggregator and reads the credentials.
- Send requests through `DbaasHelperV3`, and call `regenerateRequestId()` in lifecycle hooks so each test has its own
  `X-Request-Id`.
- Create data in the test namespaces (`dbaas-autotests-<uuid>`) and delete it in `@AfterEach` or `@AfterAll`.
- Guard a test that needs a specific adapter with `assumeTrue(helperV3.hasAdapterOfType(...))`.
- Wait with a Failsafe `RetryPolicy` (`AbstractIT.DEFAULT_RETRY_POLICY`), never with `Thread.sleep`.

### Tags

Tag an integration test when a tag applies, one annotation per tag:

- **Database type:** `postgresql`, `mongodb`, `redis`, `cassandra`, `opensearch`, `clickhouse`, `arangodb`, when the
  test creates or works with databases of that type. Find the type in type constants, `hasAdapterOfType` guards or
  helper arguments, including the ones in `@BeforeAll` and shared helpers.
- **Area:** `backup` for backup and restore, `bg` for Blue-Green, `Smoke` for a fast sanity check.

Put the tag on the class when every method fits, otherwise on the methods. A test with no specific database type and no
area, such as a health check, stays untagged.

### Running Them

Integration tests are skipped by default (`skipIT=true`) and need a deployed stack:

```bash
helmfile -e ci sync          # from bootstrap/helmfile, on a Kind cluster
mvn verify -DskipIT=false
```

`maven.test.failure.ignore` is `true` in the root `pom.xml`, so a green build doesn't mean the integration tests
passed. Check the reports.

## CI

- `build-dbaas-aggregator.yml` builds the aggregator and runs its tests on every pull request.
- `build-dbaas-operator.yml` builds and tests the operator, and checks that generated code and CRDs are up to date.
- `test-it.yml` deploys the stack to Kind and runs the integration tests. On pull requests it runs only for
  `chore(deps)` titles; start it manually when another change needs it.
- `build-helm-charts.yml` lints and renders the aggregator and operator charts, and builds the transfer image without
  pushing it, on pull requests that change the charts or the image build. On every push to `main` it publishes the
  `qubership-dbaas-transfer:main` image, which carries the two charts as stored in this repository
  (`/charts/dbaas-aggregator`, `/charts/dbaas-operator`). A manual run publishes `qubership-dbaas-transfer:<branch>`,
  with `/` in the branch name replaced by `_`. Consumers of the image, such as the internal Netcracker pipeline, derive
  their own deployment charts from it.
- Releases are manual, through `release-dbaas.yml`: it creates the `vX.Y.Z` tag, deploys the Maven artifacts, builds the
  service images, and publishes the `vX.Y.Z` GitHub Release. Its last job runs `release-helm-charts.yml`, which creates
  the plain `X.Y.Z` tag and GitHub Release for the charts and builds the versioned `qubership-dbaas-transfer:X.Y.Z`
  image with the same two charts. The charts come from `main` as it is when that job runs, which can include commits
  merged after the release started.
- To retry a failed chart release, use "Re-run failed jobs" on the failed run. A new run of `release-helm-charts.yml`
  with the same version works only if the failure came before the `release-X.Y.Z` branch was pushed; otherwise delete
  that branch, and the `X.Y.Z` tag and GitHub Release if they exist, first.
- Pull request titles and commits follow Conventional Commits; CI checks both.
