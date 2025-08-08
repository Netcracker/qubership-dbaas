package org.qubership.cloud.dbaas.rsql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPart;
import org.qubership.cloud.dbaas.rsql.model.QueryPreparationPartOverrideConfig;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.util.Optional;

public final class QueryPreparationUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private QueryPreparationUtils() {}

    public static Object convertToType(String value, TypeReference<?> typeReference) {
        try {
            return objectMapper.readValue(value, typeReference);
        } catch (IOException e) {
            throw new RuntimeException(String.format(
                "Error happened during converting value '%s' to type %s: %s",
                value, typeReference.getType().getTypeName(), e.getMessage()
            ), e);
        }
    }

    public static String obtainParamName(@NotNull QueryPreparationPart queryPreparationPart,
                                         QueryPreparationPartOverrideConfig overrideConfig) {
        return Optional.ofNullable(overrideConfig)
            .map(QueryPreparationPartOverrideConfig::getParamName)
            .orElse(queryPreparationPart.getSelector());
    }

    public static String obtainParamOperator(@NotNull QueryPreparationPart queryPreparationPart,
                                             QueryPreparationPartOverrideConfig overrideConfig) {
        return Optional.ofNullable(overrideConfig)
            .map(QueryPreparationPartOverrideConfig::getRsqlAndQueryOperators)
            .map(rsqlAndQueryOperators -> rsqlAndQueryOperators.get(queryPreparationPart.getRsqlOperator()))
            .orElse(queryPreparationPart.getQueryOperator());
    }

    public static Object obtainParamValue(@NotNull QueryPreparationPart queryPreparationPart,
                                          QueryPreparationPartOverrideConfig overrideConfig) {
        return Optional.ofNullable(overrideConfig)
            .map(QueryPreparationPartOverrideConfig::getParamValue)
            .orElse(queryPreparationPart.getArgumentsDependingOnRsqlOperator());
    }

    public static String obtainQueryTemplate(@NotNull QueryPreparationPart queryPreparationPart,
                                             QueryPreparationPartOverrideConfig overrideConfig) {
        return Optional.ofNullable(overrideConfig)
            .map(QueryPreparationPartOverrideConfig::getQueryTemplate)
            .orElse(queryPreparationPart.getQueryTemplate());
    }

    public static TypeReference<?> obtainParamValueMappingType(QueryPreparationPartOverrideConfig overrideConfig) {
        return Optional.ofNullable(overrideConfig)
            .map(QueryPreparationPartOverrideConfig::getParamValueMappingType)
            .orElse(null);
    }
}
