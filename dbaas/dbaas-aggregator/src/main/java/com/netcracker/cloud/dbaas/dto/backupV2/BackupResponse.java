package com.netcracker.cloud.dbaas.dto.backupV2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.netcracker.cloud.dbaas.enums.BackupStatus;
import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.utils.validation.group.BackupGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Response containing backup operation details")
public class BackupResponse {

    @Schema(
            description = "Unique name of the backup",
            examples = {
                    "before-prod-update-20251013T1345-G5s8"
            },
            required = true
    )
    @NotBlank
    private String backupName;
    @Schema(
            description = "Name of the storage backend containing the backup",
            examples = {
                    "s3-backend"
            },
            required = true
    )
    @NotBlank
    private String storageName;
    @Schema(
            description = "Path to the backup file in the storage",
            examples = {
                    "/backups"
            },
            required = true
    )
    @NotBlank
    private String blobPath;
    @Schema(
            description = "How to handle external databases during backup",
            examples = {
                    "FAIL"
            },
            implementation = ExternalDatabaseStrategy.class
    )
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy;
    @Schema(
            description = "Whether non‑backupable databases were ignored during backup",
            examples = {
                    "false"
            }
    )
    @NotNull
    private boolean ignoreNotBackupableDatabases;
    @Schema(
            description = "Filter criteria",
            implementation = FilterCriteria.class
    )
    @Valid
    @ConvertGroup(to = BackupGroup.class)
    private FilterCriteria filterCriteria;

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
    @NotNull
    private Integer total;
    @Schema(
            description = "Number of databases successfully backed up",
            examples = {
                    "3"
            }
    )
    @NotNull
    private Integer completed;
    @Schema(
            description = "Total size of the backup in bytes",
            examples = {
                    "1073741824"
            }
    )
    @NotNull
    private Long size;
    @Schema(
            description = "Error details if the backup failed",
            examples = {
                    "Failed to connect to database"
            }
    )
    private String errorMessage;

    @Schema(
            description = "List of logical backups",
            implementation = LogicalBackupResponse.class,
            type = SchemaType.ARRAY
    )
    private List<LogicalBackupResponse> logicalBackups;

    @Schema(
            description = "List of external databases",
            implementation = BackupExternalDatabaseResponse.class,
            type = SchemaType.ARRAY
    )
    private List<BackupExternalDatabaseResponse> externalDatabases;

    @JsonIgnore
    private String digest;
}
