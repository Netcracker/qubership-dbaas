package org.qubership.cloud.dbaas.rsql.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
@Builder
public class QueryPreparationOverrideConfig {

    private final Map<String, Set<String>> supportedSelectorsAndRsqlOperators;
    private final Map<String, QueryPreparationPartOverrideConfig> selectorsAndGlobalOverrideConfigs;
}
