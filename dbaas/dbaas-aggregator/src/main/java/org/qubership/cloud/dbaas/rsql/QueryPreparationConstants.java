package org.qubership.cloud.dbaas.rsql;

import java.util.Map;
import java.util.Set;

public final class QueryPreparationConstants {

    public static final Map<String, String> DEFAULT_SUPPORTED_RSQL_AND_QUERY_OPERATORS =
        Map.of(
            "==", "=",
            "!=", "!=",
            "=gt=", ">",
            "=lt=", "<",
            "=ge=", ">=",
            "=le=", "<=",
            "=in=", "IN",
            "=out=", "NOT IN"
        );

    public static final Set<String> STRING_RSQL_OPERATORS =  Set.of("==", "!=");
    public static final Set<String> ARRAY_RSQL_OPERATORS =  Set.of("=in=", "=out=");

    public static final String DEFAULT_QUERY_PREPARATION_PART_QUERY_TEMPLATE = "${paramName} ${paramOperator} ${paramValue}";
    public static final String QUERY_PREPARATION_PART_QUERY_TEMPLATE_NAME_FORMAT = "selector_%s_query";
    public static final String PARAM_VALUE_NAME_FORMAT = "param_%s_value_%s";

    public static final String SELECTOR = "selector";
    public static final String RSQL_OPERATOR = "rsqlOperator";
    public static final String ARGUMENTS = "arguments";
    public static final String QUERY_OPERATOR = "queryOperator";
    public static final String PARAM_NAME = "paramName";
    public static final String PARAM_OPERATOR = "paramOperator";
    public static final String PARAM_VALUE = "paramValue";

    private QueryPreparationConstants() {}
}
