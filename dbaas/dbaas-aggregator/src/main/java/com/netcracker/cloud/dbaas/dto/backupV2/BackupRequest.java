package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import jakarta.validation.Valid;
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
    @Schema(
            description = "Unique name for the backup",
            examples = {
                    "before-prod-update-20251013T1345-G5s8"
            },
            required = true)
    private String backupName;

    @NotBlank
    @Schema(
            description = "Identifier of the storage backend",
            examples = {
                    "s3-backend"
            },
            required = true)
    private String storageName;

    @NotBlank
    @Schema(
            description = "Path in the storage where backup will be stored",
            examples = {
                    "/backups/before-prod-update-20251013T1345-G5s8/logical/dbaas/"
            },
            required = true)
    private String blobPath;

    @Schema(
            description = "Filter criteria")
    @Valid
    @NotNull
    private FilterCriteria filterCriteria;

    @Schema(
            description = "How to handle external databases during backup",
            examples = {
                    "FAIL"
            },
            implementation = ExternalDatabaseStrategy.class,
            defaultValue = "FAIL"
    )
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy = ExternalDatabaseStrategy.FAIL;

    @NotNull
    @Schema(
            description = "Whether non-backupable databases should be ignored during backup",
            examples = {
                    "false"
            },
            defaultValue = "false"
    )
    private Boolean ignoreNotBackupableDatabases = false;
}
