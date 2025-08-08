package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

@Data
@Schema(description = "Errors during the migration process")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
@EqualsAndHashCode
public class FailureRegistrationV3 {
    @Schema(required = true, description = "Record Id")
    @NonNull
    private String id;

    @Schema(required = true, description = "Message about error")
    @NonNull
    private String message;
}
