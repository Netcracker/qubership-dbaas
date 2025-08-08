package org.qubership.cloud.dbaas.dto.v3;


import org.qubership.cloud.dbaas.converter.ListConverter;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import jakarta.persistence.Convert;
import java.util.List;

@Data
@Schema(description = "V3 Request model to continue register new roles")
@NoArgsConstructor
@EqualsAndHashCode
public class InstructionRequestV3 {
    @Schema(description = "Databases for which roles have been successfully created")
    @Convert(converter = ListConverter.class)
    private List<SuccessRegistrationV3> success;

    @Schema(description = "Errors when creating roles")
    private FailureRegistrationV3 failure;
}
