package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Request to restore a database from a backup")
public class RestoreRequest {
    @Schema(
            description = "Unique identifier for the restore operation",
            required = true,
            examples = {
                    "restore-before-prod-update-20251203T1020-4t6S"
            }
    )
    private String restoreName;
    @Schema(
            description = "Name of the storage backend containing the backup",
            required = true,
            examples = {
                    "s3-backend"
            }
    )
    private String storageName;
    @Schema(
            description = "Path to the backup file in the storage",
            required = true,
            examples = {
                    "/backups"
            }
    )
    private String blobPath;
    @Schema(
            description = "Filter criteria"
    )
    @Valid
    private FilterCriteria filterCriteria;
    @Schema(
            description = "Mapping to use for the restore operation")
    private Mapping mapping;

    @Schema(
            description = "How to handle external databases during restore",
            examples = {
                    "FAIL"
            },
            implementation = ExternalDatabaseStrategy.class,
            defaultValue = "FAIL"
    )
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy = ExternalDatabaseStrategy.FAIL;
}
