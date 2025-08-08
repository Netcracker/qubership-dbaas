package org.qubership.cloud.dbaas.rsql.model;

import lombok.Data;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Data
public class QueryPreparation {

    public static final QueryPreparation EMPTY = new QueryPreparation(StringUtils.EMPTY, Collections.emptyMap());

    private final String queryTemplate;
    private final Map<String, QueryPreparationPart> selectorsAndQueryPreparationParts;

    public PreparedQuery prepareQuery() {
        return prepareQuery(null);
    }

    public PreparedQuery prepareQuery(Map<String, QueryPreparationPartOverrideConfig> selectorsAndOnetimeOverrideConfigs) {
        var parameterNamesAndValuesForQueryTemplate = new HashMap<String, String>();
        var parameterNamesAndValuesForPreparedQuery = new HashMap<String, Object>();

        for (var entry : MapUtils.emptyIfNull(selectorsAndQueryPreparationParts).entrySet()) {
            var selector = entry.getKey();
            var queryPreparationPart = entry.getValue();

            var onetimeOverrideConfig = MapUtils.emptyIfNull(selectorsAndOnetimeOverrideConfigs).get(selector);
            var preparedQuery = queryPreparationPart.prepareQuery(onetimeOverrideConfig);
            var query = preparedQuery.getQuery();
            var queryTemplateName = queryPreparationPart.getQueryTemplateName();

            parameterNamesAndValuesForQueryTemplate.put(queryTemplateName, query);
            parameterNamesAndValuesForPreparedQuery.putAll(preparedQuery.getParameterNamesAndValues());
        }

        var stringSubstitutor = new StringSubstitutor(parameterNamesAndValuesForQueryTemplate);
        var query = stringSubstitutor.replace(queryTemplate);

        return new PreparedQuery(query, parameterNamesAndValuesForPreparedQuery);
    }
}
