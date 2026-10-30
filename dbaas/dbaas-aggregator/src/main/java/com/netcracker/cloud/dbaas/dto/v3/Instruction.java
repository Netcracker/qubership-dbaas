package com.netcracker.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "V3 Response model for starting the migration procedure")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@EqualsAndHashCode
public class Instruction {
    @Schema(required = true, description = "Current instruction Id")
    @NonNull
    private UUID id;

    @Schema(required = true, description = "Connection properties properties that require additional roles")
    @NonNull
    private List<AdditionalRoles> additionalRoles;
}
