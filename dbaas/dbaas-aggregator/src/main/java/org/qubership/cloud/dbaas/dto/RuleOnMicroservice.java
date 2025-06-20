package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Rule allows to define physical database for new logical databases on microservice")
public class RuleOnMicroservice {
    @Schema(
            required = true,
            description = "Label uses to find physical DB which contains this label")
    private String label;

    public RuleOnMicroservice(RuleOnMicroservice ruleOnMicroservice) {
        this.label = ruleOnMicroservice.getLabel();
    }
}
