package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Current status of a backup operation")
public class BackupStatusResponse {
    //TODO replace with enum
    @Schema(description = "Current state of the backup operation", required = true)
    private String status;
}
