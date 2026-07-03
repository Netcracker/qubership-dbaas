package com.netcracker.cloud.dbaas.rsql.model;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

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
