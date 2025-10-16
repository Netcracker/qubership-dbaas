package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.BackupStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Current status of a backup operation")
public class BackupStatusResponse {
    @Schema(
            description = "Current state of the backup operation",
            required = true,
            implementation = BackupStatus.class)
    private BackupStatus status;
    @Schema(
            description = "Total number of databases being backed up",
            examples = {
                    "5"
            }
    )
    private Integer total;
    @Schema(
            description = "Number of databases successfully backed up",
            examples = {
                    "3"
            }
    )
    private Integer completed;
    @Schema(
            description = "Total size of the backup in bytes",
            examples = {
                    "1073741824"
            }
    )
    private Long size;
    @Schema(
            description = "Error details if the backup failed",
            examples = {
                    "Failed to connect to database"
            }
    )
    private String errorMessage;
}
