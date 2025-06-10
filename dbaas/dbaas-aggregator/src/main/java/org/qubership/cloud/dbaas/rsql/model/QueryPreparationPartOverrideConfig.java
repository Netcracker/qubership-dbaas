package org.qubership.cloud.dbaas.rsql.model;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class QueryPreparationPartOverrideConfig {

    private final String queryTemplate;
    private final String paramName;
    private final Object paramValue;
    private final TypeReference<?> paramValueMappingType;
    private final Map<String, String> rsqlAndQueryOperators;
}
