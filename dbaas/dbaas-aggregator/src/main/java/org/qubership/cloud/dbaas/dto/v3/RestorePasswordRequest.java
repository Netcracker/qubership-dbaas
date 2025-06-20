package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "Request to start passwords restoration")
public class RestorePasswordRequest {
    @Schema(required = true, description = "Id of physical database where is needed to restore passwords")
    private String physicalDbId;

    @Schema(required = true, description = "Type of physical database where is needed to restore passwords (ex. opensearch)")
    private String type;

    @Schema(description = "Field with additional information for adapter")
    private Map<String, Object> settings;
}
