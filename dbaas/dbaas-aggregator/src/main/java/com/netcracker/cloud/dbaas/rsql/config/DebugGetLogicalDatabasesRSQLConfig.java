package org.qubership.cloud.dbaas.rsql.config;

import org.qubership.cloud.dbaas.rsql.QueryPreparationConstants;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationOverrideConfig;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPartOverrideConfig;

import java.util.Map;
import java.util.Set;

public final class DebugGetLogicalDatabasesRSQLConfig {

    private static final Map<String, String> OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR = Map.of("!=","IS DISTINCT FROM");

    private static final Map<String, Set<String>> SUPPORTED_SELECTORS_AND_RSQL_OPERATORS = Map.of(
        "namespace", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "microservice", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "tenantId", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "logicalDbName", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "bgVersion", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "type", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "roles", QueryPreparationConstants.ARRAY_RSQL_OPERATORS,
        "name", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "physicalDbId", QueryPreparationConstants.STRING_RSQL_OPERATORS,
        "physicalDbAdapterUrl", QueryPreparationConstants.STRING_RSQL_OPERATORS
    );

    private static final Map<String, QueryPreparationPartOverrideConfig> SELECTORS_AND_GLOBAL_OVERRIDE_CONFIGS = Map.of(
        "namespace",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.classifier->>'${paramName}' ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "microservice",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.classifier->>'microserviceName' ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "tenantId",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.classifier->>'${paramName}' ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "logicalDbName",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("""
                    (logical_database.classifier->'custom_keys'->>'logicalDBName' ${paramOperator} ${paramValue}
                        OR logical_database.classifier->'custom_keys'->>'logicalDbName' ${paramOperator} ${paramValue}
                        OR logical_database.classifier->'custom_keys'->>'logicalDbId' ${paramOperator} ${paramValue}
                        OR logical_database.classifier->'customKeys'->>'logicalDBName' ${paramOperator} ${paramValue}
                        OR logical_database.classifier->'customKeys'->>'logicalDbName' ${paramOperator} ${paramValue}
                        OR logical_database.classifier->'customKeys'->>'logicalDbId' ${paramOperator} ${paramValue}
                    )
                    """)
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "bgVersion",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.bgversion ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "type",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.${paramName} ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "roles",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("""
                    EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements(logical_database.connection_properties::jsonb) AS conn_props_jsonb
                        WHERE conn_props_jsonb->>'role' ${paramOperator} ${paramValue}
                    )
                    """)
            .build(),

        "name",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.${paramName} ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "physicalDbId",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("logical_database.physical_database_id ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build(),

        "physicalDbAdapterUrl",
        QueryPreparationPartOverrideConfig.builder()
            .queryTemplate("external_adapter_registration.address ${paramOperator} ${paramValue}")
            .rsqlAndQueryOperators(OVERRIDDEN_NOT_EQUAL_RSQL_AND_QUERY_OPERATOR)
            .build()
    );

    public static final QueryPreparationOverrideConfig OVERRIDE_CONFIG =
        QueryPreparationOverrideConfig.builder()
            .supportedSelectorsAndRsqlOperators(SUPPORTED_SELECTORS_AND_RSQL_OPERATORS)
            .selectorsAndGlobalOverrideConfigs(SELECTORS_AND_GLOBAL_OVERRIDE_CONFIGS)
            .build();
    
    private DebugGetLogicalDatabasesRSQLConfig() {}
}
