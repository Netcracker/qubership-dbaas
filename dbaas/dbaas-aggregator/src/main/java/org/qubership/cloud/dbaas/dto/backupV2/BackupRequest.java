package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Request to create a database backup")
public class BackupRequest {
    @Schema(description = "Unique name for the backup", example = "before-prod-update-20251013T1345-G5s8", required = true)
    private String backupName;
    @Schema(description = "Identifier of the storage backend", example = "s3-backend", required = true)
    private String storageName;
    @Schema(description = "Path in the storage where backup will be stored", example = "/backups/before-prod-update-20251013T1345-G5s8/logical/dbaas/", required = true)
    private String blobPath;
    @Schema(
            description = "Filter criteria"
    )
    private FilterCriteria filterCriteria;
    @Schema(
            description = "How to handle external databases during backup",
            example = "fail",
            implementation = ExternalDatabaseStrategy.class)
    private ExternalDatabaseStrategy externalDatabaseStrategy;
    @Schema(description = "Whether external databases were skipped during the backup", example = "false")
    private boolean ignoreNotBackupableDatabases;
}
