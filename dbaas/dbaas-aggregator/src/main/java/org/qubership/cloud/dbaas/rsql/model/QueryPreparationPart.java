package org.qubership.cloud.dbaas.rsql.model;

import org.qubership.cloud.dbaas.rsql.QueryPreparationConstants;
import org.qubership.cloud.dbaas.rsql.QueryPreparationUtils;
import lombok.Data;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.text.StringSubstitutor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class QueryPreparationPart {

    private final String selector;
    private final String rsqlOperator;
    private final List<String> arguments;

    private final String queryOperator;
    private final String queryTemplate;
    private final String queryTemplateName;

    private QueryPreparationPartOverrideConfig globalOverrideConfig;

    public PreparedQuery prepareQuery() {
        return prepareQuery(null);
    }

    public PreparedQuery prepareQuery(QueryPreparationPartOverrideConfig onetimeOverrideConfig) {
        var overrideConfig = ObjectUtils.firstNonNull(onetimeOverrideConfig, globalOverrideConfig);
        var parameterNamesAndValuesForQueryTemplate = createBaseParameterNamesAndValuesForQueryTemplate();

        var paramNameForPreparedQuery = String.format(QueryPreparationConstants.PARAM_VALUE_NAME_FORMAT,
            selector, System.nanoTime()
        );
        var paramValueForPreparedQuery = QueryPreparationUtils.obtainParamValue(this, overrideConfig);
        var paramValueMappingType = QueryPreparationUtils.obtainParamValueMappingType(overrideConfig);

        if (paramValueMappingType != null && paramValueForPreparedQuery instanceof String paramValueStr) {
            paramValueForPreparedQuery = QueryPreparationUtils.convertToType(paramValueStr, paramValueMappingType);
        }

        var paramNameForQueryTemplate = QueryPreparationUtils.obtainParamName(this, overrideConfig);
        var paramOperatorForQueryTemplate = QueryPreparationUtils.obtainParamOperator(this, overrideConfig);
        var paramValueForQueryTemplate = ":" + paramNameForPreparedQuery;

        parameterNamesAndValuesForQueryTemplate.put(QueryPreparationConstants.PARAM_NAME, paramNameForQueryTemplate);
        parameterNamesAndValuesForQueryTemplate.put(QueryPreparationConstants.PARAM_OPERATOR, paramOperatorForQueryTemplate);
        parameterNamesAndValuesForQueryTemplate.put(QueryPreparationConstants.PARAM_VALUE, paramValueForQueryTemplate);

        var stringSubstitutor = new StringSubstitutor(parameterNamesAndValuesForQueryTemplate);
        var obtainedQueryTemplate = QueryPreparationUtils.obtainQueryTemplate(this, overrideConfig);

        var query = stringSubstitutor.replace(obtainedQueryTemplate);
        var parameterNamesAndValuesForPreparedQuery = Map.of(paramNameForPreparedQuery, paramValueForPreparedQuery);

        return new PreparedQuery(query, parameterNamesAndValuesForPreparedQuery);
    }

    protected Map<String, Object> createBaseParameterNamesAndValuesForQueryTemplate() {
        var queryParameterNamesAndValues = HashMap.<String, Object>newHashMap(7);

        queryParameterNamesAndValues.put(QueryPreparationConstants.SELECTOR, selector);
        queryParameterNamesAndValues.put(QueryPreparationConstants.RSQL_OPERATOR, rsqlOperator);
        queryParameterNamesAndValues.put(QueryPreparationConstants.ARGUMENTS, arguments);
        queryParameterNamesAndValues.put(QueryPreparationConstants.QUERY_OPERATOR, queryOperator);

        return queryParameterNamesAndValues;
    }

    public Object getArgumentsDependingOnRsqlOperator() {
        if ("=in=".equals(rsqlOperator) || "=out=".equals(rsqlOperator)) {
            return arguments;
        }
        return arguments.getFirst();
    }
}
