package org.qubership.cloud.dbaas;

public class DbaasApiPath {
    public static final String
            ASYNC_PARAMETER = "async",
            NAMESPACE_PARAMETER = "namespace",
            API_VERSION = "/api-version",
            API = "/api",
            DBAAS_DECLARATIVE = API + "/dbaas/declarative",
            LIST_DATABASES_PATH = "/list",
            FIND_BY_NAME_PATH = "/find-by-name/{dbname}",
            FIND_LOST_DB_PATH = "/lost",
            FIND_GHOST_DB_PATH = "/ghost",
            VERSION_1 = "v1",
            VERSION_2 = "v2",
            DBAAS_CONFIGS_V1 = API + "/declarations" + "/" + VERSION_1,

            VERSION_3 = "v3",
            API_PATH_V3 = API + "/" + VERSION_3,
            DBAAS_PATH_V3 = API_PATH_V3 + "/dbaas",
            BALANCING_RULES_V3 = DBAAS_PATH_V3 + "/{" + NAMESPACE_PARAMETER + "}/physical_databases",
            PERMANENT_BALANCING_RULES_V3 = DBAAS_PATH_V3 + "/balancing/rules/permanent",
            DATABASES_WITHOUT_NAMESPACE_PATH_V3 = DBAAS_PATH_V3 + "/databases",
            DATABASES_PATH_V3 = DBAAS_PATH_V3 + "/{" + NAMESPACE_PARAMETER + "}" + "/databases",
            BACKUPS_PATH_V3 = DBAAS_PATH_V3 + "/{" + NAMESPACE_PARAMETER + "}" + "/backups",
            BACKUPS_BULK_PATH_V3 = DBAAS_PATH_V3 + "/backups",
            DATABASE_OPERATION_PATH_V3 = DBAAS_PATH_V3 + "/namespaces" + "/{" + NAMESPACE_PARAMETER + "}",
            DATABASE_GLOBAL_OPERATION_PATH_V3 = DATABASES_WITHOUT_NAMESPACE_PATH_V3,
            INTERNAL_PHYSICAL_DATABASES_PATH = DBAAS_PATH_V3 + "/internal/physical_databases",
            DEBUG_INTERNAL_PATH_V3 = DBAAS_PATH_V3 + "/debug/internal",
            MIGRATION_PATH_V3 = DBAAS_PATH_V3 + "/migration",
            DATABASES_MIGRATION_PATH_V3 = MIGRATION_PATH_V3 + "/databases",
            PHYSICAL_DATABASES_PATH_V3 = DBAAS_PATH_V3 + "/{type}/physical_databases",

            DBAAS_BLUE_GREEN_PATH_V1 = API + "/bluegreen/" + VERSION_1 + "/operation",
            USERS_PATH_V3 = DBAAS_PATH_V3 + "/users",
                    ACCESS_GRANTS_SUBPATH_V3 = "/services/{serviceName}/access-grants",
                    GET_OVERALL_STATUS_PATH = "/info";
}
