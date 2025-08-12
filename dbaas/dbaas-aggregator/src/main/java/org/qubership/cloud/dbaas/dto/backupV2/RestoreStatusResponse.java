package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@AllArgsConstructor
@Schema(description = "Current status of a restore operation")
public class RestoreStatusResponse {
    //TODO replace with enum
    @Schema(description = "Current state of the restore operation", required = true)
    private String status;
}
