package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.BackupTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
@Schema(description = "Logical backup details")
public class LogicalBackupResponse {

    @Schema(description = "Name of the logical backup in adapter", required = true)
    String logicalBackupName;
    @Schema(
            description = "Unique identifier of the adapter",
            examples = {
                    "4e1e9922-e5bf-490d-bb57-18a14de593c6"
            },
            required = true
    )
    private String adapterId;
    @Schema(description = "Type of the adapter", required = true)
    private String type;

    @Schema(
            description = "Current state of the backup databases of one adapter",
            required = true,
            implementation = BackupTaskStatus.class)
    private BackupTaskStatus status;

    private String errorMessage;

    private Instant creationTime;

    private Instant completionTime;

    @Schema(
            description = "List of logical backup databases",
            required = true
    )
    private List<BackupDatabaseResponse> backupDatabases;
}
