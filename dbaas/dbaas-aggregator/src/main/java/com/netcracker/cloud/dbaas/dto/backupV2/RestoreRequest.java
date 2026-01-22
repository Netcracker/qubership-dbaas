package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.utils.validation.group.RestoreGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@Schema(description = "Request to restore a database from a backup")
public class RestoreRequest {
    @Schema(
            description = "Unique identifier of the restore",
            required = true,
            examples = {
                    "restore-before-prod-update-20251203T1020-4t6S"
            }
    )
    @NotBlank
    private String restoreName;
    @Schema(
            description = "Name of the storage backend containing the restore",
            required = true,
            examples = {
                    "s3-backend"
            }
    )
    @NotBlank
    private String storageName;
    @Schema(
            description = "Path to the restore file in the storage",
            required = true,
            examples = {
                    "/backups"
            }
    )
    @NotBlank
    private String blobPath;
    @Schema(
            description = "Filter criteria",
            implementation = FilterCriteria.class
    )
    @Valid
    @ConvertGroup(to = RestoreGroup.class)
    private FilterCriteria filterCriteria;
    @Schema(
            description = "Mapping to use for the restore operation",
            implementation = Mapping.class
    )
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
