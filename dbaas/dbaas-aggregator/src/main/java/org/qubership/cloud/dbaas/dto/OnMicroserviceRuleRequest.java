package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.List;


@Data
@AllArgsConstructor
@NoArgsConstructor(force = true)
@Schema(description = "Rule registration request allows to " +
        "add a new rule for the specific type of logical databases, " +
        "which would be applied in specific order.")
public class OnMicroserviceRuleRequest {
    @Schema(required = true, description = "Type of physical database which logical base belongs to")
    @NonNull
    private String type;

    @Schema(description = "List of rules to microservices. Allows to define physical database")
    private List<RuleOnMicroservice> rules;

    @Schema(required = true, description = "List of microservice names to which the specified rule has a place to be")
    private List<String> microservices;
}
