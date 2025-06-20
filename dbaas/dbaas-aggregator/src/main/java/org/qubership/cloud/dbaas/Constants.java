package org.qubership.cloud.dbaas;

import com.google.common.collect.Sets;

import java.util.Set;

public class Constants {

    private Constants() {
    }
    public static final Set<String> KV_DBS_WITH_SUPPORTED_MIGRATION = Sets.newHashSet("redis", "opensearch", "arangodb");

    public static final String
            H2_DATASOURCE = "h2DataSource",
            H2_TRANSACTION_MANAGER = "h2TransactionManager",
            H2_ENTITY_MANAGER = "h2EntityManager",
            H2_FLYWAY = "h2Flyway",
            PG_DATASOURCE = "pgDataSource",
            PG_ENTITY_MANAGER = "pgEntityManager",
            ROLE = "role",
            MICROSERVICE_NAME = "microserviceName",
            SCOPE = "scope",
            TENANT_ID = "tenantId",
            SCOPE_VALUE_TENANT = "tenant",
            SCOPE_VALUE_SERVICE = "service",
            V3_TRANSFORMATION = "V3_TRANSFORMATION",
            DUPLICATED_DATABASE = "MIGRATION_DUPLICATED_DATABASE",
            NAMESPACE = "namespace",
            TLS = "tls",
            TLS_NOT_STRICT = "tlsNotStrict",
            POSTGRESQL_SSL_FACTORY_CLASS = "org.postgresql.ssl.SingleCertValidatingFactory",
            CLONE_MODE = "clone",
            NEW_MODE = "new",
            ACTIVE_STATE = "active",
            CANDIDATE_STATE = "candidate",
            IDLE_STATE = "idle",
            LEGACY_STATE = "legacy",
            VERSION_STATE = "version",
            STATIC_STATE = "static",
            DATABASE_DECLARATION_CONFIG_TYPE = "DatabaseDeclaration",
            DB_POLICY_CONFIG_TYPE = "DbPolicy",
            WARMUP_OPERATION = "warmup",
            APPLY_CONFIG_OPERATION = "applyConfigs",
            NAMESPACE_CLEANER = "NAMESPACE_CLEANER",
            DB_CLIENT = "DB_CLIENT",
            BACKUP_MANAGER = "BACKUP_MANAGER",
            DB_EDITOR = "DBAAS_DB_EDITOR",
            MIGRATION_CLIENT = "MIGRATION_CLIENT",
            DISCR_TOOL_CLIENT = "DISCR_TOOL_CLIENT";

}
