package org.qubership.cloud.dbaas.rsql;

import org.qubership.cloud.dbaas.rsql.model.QueryPreparation;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationOverrideConfig;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPart;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.RSQLParserException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public class QueryPreparationRSQLProcessor {

    private final RSQLParser parser = new RSQLParser();

    private final QueryPreparationRSQLVisitor visitor;

    public QueryPreparation parseAndConstruct(String rsqlQuery) {
        return parseAndConstruct(rsqlQuery, null);
    }

    public QueryPreparation parseAndConstruct(String rsqlQuery, QueryPreparationOverrideConfig config) {
        if (StringUtils.isBlank(rsqlQuery)) {
            return QueryPreparation.EMPTY;
        }

        var rootNode = parser.parse(rsqlQuery);
        var queryPreparation = rootNode.accept(visitor);

        if (config != null) {
            handleQueryPreparation(queryPreparation, config);
        }

        return queryPreparation;
    }

    protected void handleQueryPreparation(QueryPreparation queryPreparation, QueryPreparationOverrideConfig overrideConfig) {
        var selectorsAndQueryPreparationParts = queryPreparation.getSelectorsAndQueryPreparationParts();
        var supportedSelectorsAndRsqlOperators = overrideConfig.getSupportedSelectorsAndRsqlOperators();

        if (MapUtils.isNotEmpty(supportedSelectorsAndRsqlOperators)) {

            validateSupportedSelectorsAndRsqlOperators(
                selectorsAndQueryPreparationParts,
                supportedSelectorsAndRsqlOperators
            );
        }

        var selectorsAndGlobalOverrideConfigs = overrideConfig.getSelectorsAndGlobalOverrideConfigs();

        for (var entry : MapUtils.emptyIfNull(selectorsAndGlobalOverrideConfigs).entrySet()) {
            var selector = entry.getKey();
            var queryPreparationPart = selectorsAndQueryPreparationParts.get(selector);

            if (queryPreparationPart != null) {
                var globalOverrideConfig = entry.getValue();

                queryPreparationPart.setGlobalOverrideConfig(globalOverrideConfig);
            }
        }
    }

    protected void validateSupportedSelectorsAndRsqlOperators(Map<String, QueryPreparationPart> selectorsAndQueryPreparationParts,
                                                              Map<String, Set<String>> supportedSelectorsAndRsqlOperators) {
        var currentSelectors = selectorsAndQueryPreparationParts.keySet();
        var supportedSelectors = supportedSelectorsAndRsqlOperators.keySet();
        var unsupportedSelectors = CollectionUtils.subtract(currentSelectors, supportedSelectors);

        if (CollectionUtils.isNotEmpty(unsupportedSelectors)) {
            throw new RSQLParserException(new RuntimeException("Unsupported selectors: " + unsupportedSelectors));
        }

        for (var selectorAndQueryPreparationPartEntry : selectorsAndQueryPreparationParts.entrySet()) {
            var selector = selectorAndQueryPreparationPartEntry.getKey();
            var queryPreparationPart = selectorAndQueryPreparationPartEntry.getValue();

            var supportedRsqlOperators = supportedSelectorsAndRsqlOperators.get(selector);
            var rsqlOperator = queryPreparationPart.getRsqlOperator();

            if (!supportedRsqlOperators.contains(rsqlOperator)) {
                throw new RSQLParserException(new RuntimeException(String.format(
                    "Unsupported RSQL operator '%s' for selector: %s", rsqlOperator, selector
                )));
            }
        }
    }
}
