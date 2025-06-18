package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.dto.OnMicroserviceRuleRequest;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Debug rules request allows to validate a list of rules against a list of microservices " +
        "to check what database is going to be assigned to each microservice")
public class DebugRulesRequest {
    @Schema(required = true, description = "List of microservices")
    @NonNull
    private List<String> microservices;

    @Schema(required = true, description = "List of rules")
    @NonNull
    private List<OnMicroserviceRuleRequest> rules;
}
