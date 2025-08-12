package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Request to restore a database from a backup")
public class RestoreRequest {
    @Schema(description = "Unique identifier for the restore operation", required = true, example = "restore-before-prod-update-20251203T1020-4t6S")
    private String restoreName;
}
