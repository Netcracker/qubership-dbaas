package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.ExternalDatabaseStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Request to create a database backup")
public class BackupRequest {

    @NotBlank
    @Schema(description = "Unique name for the backup", example = "before-prod-update-20251013T1345-G5s8", required = true)
    private String backupName;

    @NotBlank
    @Schema(description = "Identifier of the storage backend", example = "s3-backend", required = true)
    private String storageName;

    @NotBlank
    @Schema(description = "Path in the storage where backup will be stored", example = "/backups/before-prod-update-20251013T1345-G5s8/logical/dbaas/", required = true)
    private String blobPath;

    @Schema(
            description = "Filter criteria")
    private FilterCriteria filterCriteria;

    @Schema(
            description = "How to handle external databases during backup",
            example = "fail",
            implementation = ExternalDatabaseStrategy.class)
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy;

    @NotNull
    @Schema(description = "Whether external databases were skipped during the backup", example = "false")
    private Boolean ignoreNotBackupableDatabases;
}
