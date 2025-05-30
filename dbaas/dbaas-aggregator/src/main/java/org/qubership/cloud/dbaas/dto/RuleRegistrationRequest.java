package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NoArgsConstructor(force = true)
@AllArgsConstructor
@Schema(description = "Rule registration request allows to " +
        "add a new rule for the specific type of logical databases, " +
        "which would be applied in specific order.")
public class RuleRegistrationRequest {
    @Schema(
            required = false,
            description = "Inside namespace+type domain, order defines which rule would be used first. The" +
                    "lesser order takes precedence. " +
                    "The order is optional; if not specified then the maximum over namespace+type domain would be calculated.")
    private Long order;
    @NonNull
    @Schema(
            required = true,
            description = "Type of database required. The rule would only work on logical databases of the specified type.")
    private String type;
    private RuleBody rule;
}
