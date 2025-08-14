package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.qubership.cloud.dbaas.entity.pg.backupV2.ExternalDatabaseStrategy;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Response containing backup operation details")
public class BackupResponse {
    @Schema(description = "Unique identifier of the backup", example = "before-prod-update-20251013T1345-G5s8", required = true)
    private String backupName;
    @Schema(description = "Name of the storage backend containing the backup", example = "s3-backend", required = true)
    private String storageName;
    @Schema(description = "Path to the backup file in the storage", example = "/backups", required = true)
    private String blobPath;
    @Schema(
            description = "How to handle external databases during backup",
            example = "fail",
            implementation = ExternalDatabaseStrategy.class
    )
    private ExternalDatabaseStrategy externalDatabaseStrategy;
    @Schema(description = "Whether external databases were skipped during the backup", example = "false")
    private boolean ignoreNotBackupableDatabases;
    @Schema(
            description = "Filter criteria"
    )
    private FilterCriteria filterCriteria;
    @Schema(description = "Status of the backup operation")
    private BackupStatusResponse status;
    @Schema(
            description = "List of logical backups"
    )
    private List<LogicalBackupResponse> logicalBackups;
}
