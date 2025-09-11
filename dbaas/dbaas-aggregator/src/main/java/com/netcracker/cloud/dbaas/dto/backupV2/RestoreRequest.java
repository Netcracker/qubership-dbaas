package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Request to restore a database from a backup")
public class RestoreRequest {
    @Schema(description = "Unique identifier for the restore operation", required = true, example = "restore-before-prod-update-20251203T1020-4t6S")
    private String restoreName;
    @Schema(description = "Name of the storage backend containing the backup", required = true, example = "s3-backend")
    private String storageName;
    @Schema(description = "Path to the backup file in the storage", required = true, example = "/backups")
    private String blobPath;
    @Schema(
            description = "Filter criteria"
    )
    private FilterCriteria filterCriteria;
    @Schema(
            description = "Mapping to use for the restore operation")
    private Mapping mapping;
}
