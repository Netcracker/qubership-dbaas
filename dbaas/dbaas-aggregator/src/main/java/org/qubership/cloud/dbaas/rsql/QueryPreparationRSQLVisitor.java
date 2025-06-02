package org.qubership.cloud.dbaas.rsql;

import org.qubership.cloud.dbaas.rsql.model.QueryPreparation;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPart;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.OrNode;
import cz.jirutka.rsql.parser.ast.RSQLVisitor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QueryPreparationRSQLVisitor implements RSQLVisitor<QueryPreparation, Void> {

    private final Map<String, String> supportedRsqlAndQueryOperators;
    private final String queryPreparationPartQueryTemplate;

    public QueryPreparationRSQLVisitor() {
        this.supportedRsqlAndQueryOperators = QueryPreparationConstants.DEFAULT_SUPPORTED_RSQL_AND_QUERY_OPERATORS;
        this.queryPreparationPartQueryTemplate = QueryPreparationConstants.DEFAULT_QUERY_PREPARATION_PART_QUERY_TEMPLATE;
    }

    @Override
    public QueryPreparation visit(AndNode node, Void param) {
        return combineQueryPreparations(extractChildQueryPreparations(node), "AND");
    }

    @Override
    public QueryPreparation visit(OrNode node, Void param) {
        return combineQueryPreparations(extractChildQueryPreparations(node), "OR");
    }

    @Override
    public QueryPreparation visit(ComparisonNode node, Void param) {
        var rsqlOperator = node.getOperator().getSymbol();
        var queryOperator = supportedRsqlAndQueryOperators.get(rsqlOperator);

        if (queryOperator == null) {
            throw new RuntimeException("Unsupported RSQL operator: " + rsqlOperator);
        }

        var selector = node.getSelector();
        var arguments = node.getArguments();

        var queryPreparationPartQueryTemplateName = getQueryPreparationPartQueryTemplateName(selector);
        var queryPreparationQueryTemplate = "${" + queryPreparationPartQueryTemplateName + "}";

        var queryPreparationPart = new QueryPreparationPart(
            selector, rsqlOperator, arguments, queryOperator, queryPreparationPartQueryTemplate, queryPreparationPartQueryTemplateName
        );

        return new QueryPreparation(queryPreparationQueryTemplate, Map.of(selector, queryPreparationPart));
    }

    protected List<QueryPreparation> extractChildQueryPreparations(LogicalNode node) {
        return node.getChildren().stream()
            .map(child -> child.accept(this))
            .toList();
    }

    protected QueryPreparation combineQueryPreparations(List<QueryPreparation> queryPreparations, String joinType) {
        var combinedQueryTemplate = queryPreparations.stream()
            .map(QueryPreparation::getQueryTemplate)
            .collect(Collectors.joining(" " + joinType + " ", "(", ")"));

        var combinedSelectorsAndQueryPreparationParts = queryPreparations.stream()
            .map(QueryPreparation::getSelectorsAndQueryPreparationParts)
            .flatMap(map -> map.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return new QueryPreparation(combinedQueryTemplate, combinedSelectorsAndQueryPreparationParts);
    }

    protected String getQueryPreparationPartQueryTemplateName(String selector) {
        return String.format(QueryPreparationConstants.QUERY_PREPARATION_PART_QUERY_TEMPLATE_NAME_FORMAT, selector);
    }
}
