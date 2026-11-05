# DBaaS mounted-secret migration

Migrate Go, Spring, and Quarkus DBaaS services from runtime REST provisioning to
`InternalDatabase` and `DatabaseSecretClaim` resources whose generated Secrets are mounted into the
application.

The skill inventories complete classifier identities, database types, creation parameters, and
requested roles before generating resources. It supports deployment-known service and tenant
identities and leaves request-context-derived tenant identities on the runtime path.

Invoke the `dbaas-mounted-secret-migration` skill from a service repository. Review the inventory and
generated diff before applying it to a cluster.

The skill is independent of `migrate-core-operator-dbaas-declarations` and does not invoke it. By
itself, it generates the `InternalDatabase` resources as well as the claims and mounts. When you
ask for both migrations, the declaration migration runs first, and this skill reuses each
`InternalDatabase` listed in its `internalDatabases` result instead of generating a second one.

An identity whose client library cannot read mounted Secrets gets no claim or mount. In a combined
migration, its `InternalDatabase` from the declaration migration stays in place.

Helm consumers must use the latest CoreTPL release that provides `coretpl.dbaasNamespace`.
