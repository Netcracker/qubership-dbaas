package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.util.List;

@Data
@Schema(description = "V3 Response model for starting the migration procedure")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@EqualsAndHashCode
public class Instruction {
    @Schema(required = true, description = "Current instruction Id")
    @NonNull
    private String id;

    @Schema(required = true, description = "Connection properties properties that require additional roles")
    @NonNull
    private List<AdditionalRoles> additionalRoles;
}
