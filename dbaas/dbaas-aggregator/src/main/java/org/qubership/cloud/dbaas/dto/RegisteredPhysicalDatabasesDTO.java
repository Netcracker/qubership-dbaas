package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.dto.v3.PhysicalDatabaseRegistrationResponseDTOV3;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
public class RegisteredPhysicalDatabasesDTO {
    @Schema(description = "List of registered physical databases with with a known identifier")
    private Map<String, PhysicalDatabaseRegistrationResponseDTOV3> identified;
}
