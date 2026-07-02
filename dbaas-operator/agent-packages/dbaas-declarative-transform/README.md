# DBaaS declarative transform

Migrate Go, Spring, and Quarkus DBaaS services from runtime provisioning to
`InternalDatabase` and `DatabaseSecretClaim` resources with generated credentials.

The skill inventories complete classifier identities, database types, creation parameters, and
requested roles before generating resources. It supports deployment-known service and tenant
identities and leaves request-context-derived tenant identities on the runtime path.

Invoke the `dbaas-declarative-transform` skill from a service repository. Review the inventory and
generated diff before applying it to a cluster.
