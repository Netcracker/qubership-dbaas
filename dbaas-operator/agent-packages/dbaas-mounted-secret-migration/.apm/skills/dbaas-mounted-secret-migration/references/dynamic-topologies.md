# Dynamic topologies

Model database identity, credential role, logical routing name, physical database ID, schema, bucket,
and shard independently.

Generate resources only when all tenants, classifiers, types, roles, and placement inputs are fixed by
rendered configuration. A default tenant is not automatically supported: trace every database, shard,
schema, and migration it initializes.

Keep runtime provisioning when identities come from request context, customers appear after deployment,
placement depends on capacity, buckets are created on first access, or mappings change dynamically. Never
generate wildcard tenants or placeholder classifiers.

Mixed services may provision metadata declaratively while retaining dynamic tenant shards. Inventory them
independently and prove a static Secret cannot satisfy a dynamic lookup. `PhysicalDatabaseId` and
logical-to-physical maps remain `BLOCKED` until a CR mapping is proven.
