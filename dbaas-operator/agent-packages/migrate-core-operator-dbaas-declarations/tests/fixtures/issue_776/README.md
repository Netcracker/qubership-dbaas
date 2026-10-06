# Issue #776 regression fixtures

Minimal, local layouts modeled on the public patterns described in issue #776, exercised through
the real writer's `--check` without any source preprocessing (see `../test_issue_776_fixtures.py`).

- `config-server-like/`: a `kind: DBaaS`/`subKind: DbPolicy` wrapper with unquoted Helm-expression
  `metadata.name`/`metadata.namespace`, Core Operator wrapper labels alongside ordinary ones, and a
  legacy policy containing only `disableGlobalPermissions: true`.
- `site-management-like/`: a `DatabaseDeclaration` with a classifier extension key, and a
  `DbPolicy` containing only `disableGlobalPermissions: false`.
