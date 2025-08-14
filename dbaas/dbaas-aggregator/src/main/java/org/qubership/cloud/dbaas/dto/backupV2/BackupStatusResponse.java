package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Current status of a backup operation")
public class BackupStatusResponse {
    @Schema(
            description = "Current state of the backup operation",
            required = true,
            implementation = BackupStatusDto.class)
    private BackupStatusDto status;
    @Schema(description = "Total number of databases being backed up", example = "5")
    private int total;
    @Schema(description = "Number of databases successfully backed up", example = "3")
    private int completed;
    @Schema(description = "Total size of the backup in bytes", example = "1073741824")
    private int size;
    @Schema(description = "Error details if the backup failed", example = "Failed to connect to database")
    private String errorMessage;
}
