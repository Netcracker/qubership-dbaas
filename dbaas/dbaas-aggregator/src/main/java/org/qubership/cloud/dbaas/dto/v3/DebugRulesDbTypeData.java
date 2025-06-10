package org.qubership.cloud.dbaas.dto.v3;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@AllArgsConstructor
@Data
public class DebugRulesDbTypeData {
    public static String MICROSERVICE_RULE_INFO = "Microservice balancing rule was applied.";
    public static String NAMESPACE_RULE_INFO = "Namespace balancing rule was applied.";
    public static String DEFAULT_DATABASE_RULE_INFO = "No applicable rules exist. Default database will be used.";
    public static String NO_SUITABLE_DATABASE_RULE_INFO = "Error: No applicable rules and default database is disabled.";

    private Map<String, String> labels;
    private String physicalDbIdentifier;
    private String appliedRuleInfo;
}