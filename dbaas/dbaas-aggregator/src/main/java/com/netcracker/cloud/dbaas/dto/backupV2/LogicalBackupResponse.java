package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.BackupTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@Schema(description = "Logical backup details")
public class LogicalBackupResponse {
    @Schema(
            description = "Identifier of the logical backup",
            examples = {"550e8400-e29b-41d4-a716-446655440000"},
            required = true
    )
    private UUID id;
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
    @Schema(description = "Error message if backup failed", examples = "Backup Not Found")
    private String errorMessage;
    @Schema(description = "Timestamp when the backup was created", examples = "2025-11-13T12:34:56Z")
    private Instant creationTime;
    @Schema(description = "Timestamp when the backup completed", examples = "2025-11-13T12:45:00Z")
    private Instant completionTime;
    @Schema(
            description = "List of logical backup databases",
            implementation = BackupDatabaseResponse.class,
            type = SchemaType.ARRAY,
            required = true
    )
    private List<BackupDatabaseResponse> backupDatabases;
}
