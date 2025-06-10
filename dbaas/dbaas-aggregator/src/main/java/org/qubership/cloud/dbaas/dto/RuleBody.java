package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "Rule allows to define physical database for new logical databases")
public class RuleBody {

    @Schema(description = "Rule type specified what configuration applies to which logic. \n" +
            " - perNamespace rule would just use " +
            "specified physical database for any " +
            "new logical database in namespace, where rule works")
    enum RuleType {
        perNamespace
    }

    @Schema(
            required = true,
            description = "Type of rule is required, it defines what logic would be used when rule is applying.")
    private RuleType type;
    @Schema(
            required = true,
            description = "Configuration contains rule-specific information: \n" +
                    " - perNamespace rule only expects phydbid (physical database identifier) " +
                    "to be specified in rule.")
    private Map<String, Object> config;
}
