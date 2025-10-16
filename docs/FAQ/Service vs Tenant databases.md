## Multitenancy

**Multitenancy** is when several different cloud customers are accessing the same computing resources. The multitenancy approach assumes that services can work with different configurations and input data sets at the same time, but these configurations and data should be **isolated** from one another per Tenant. Isolation is a key point in the multitenancy support. Especially on database layer. So each **tenant** works with it's own data. And each application may have several tenants.

## Service vs Tenant database

Service databases:

- one per microservice
- contains common configuration needed for service work
- in most cases, service databases are created at application start-up

Tenant databases:

- one per tenant - a new database is provisioned for each new tenant based on tenantId value
- tenant database stores data belonging to exactly one tenant
- tenant databases are created ad-hoc, after receiving a request for creation with tenantId
