package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.BackupStatus;
import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Response containing backup operation details")
public class BackupResponse {

    @NotBlank
    @Schema(
            description = "Unique identifier of the backup",
            examples = {
                    "before-prod-update-20251013T1345-G5s8"
            },
            required = true
    )
    private String backupName;

    @NotBlank
    @Schema(
            description = "Name of the storage backend containing the backup",
            examples = {
                    "s3-backend"
            },
            required = true
    )
    private String storageName;

    @NotBlank
    @Schema(
            description = "Path to the backup file in the storage",
            examples = {
                    "/backups"
            },
            required = true
    )
    private String blobPath;

    @Schema(
            description = "How to handle external databases during backup",
            examples = {
                    "fail"
            },
            implementation = ExternalDatabaseStrategy.class
    )
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy;

    @NotNull
    @Schema(
            description = "Whether external databases were skipped during the backup",
            examples = {
                    "false"
            }
    )
    private boolean ignoreNotBackupableDatabases;

    @Schema(
            description = "Filter criteria"
    )
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
    private Integer total;
    @Schema(
            description = "Number of databases successfully backed up",
            examples = {
                    "3"
            }
    )
    private Integer completed;
    @Schema(
            description = "Total size of the backup in bytes",
            examples = {
                    "1073741824"
            }
    )
    private Long size;
    @Schema(
            description = "Error details if the backup failed",
            examples = {
                    "Failed to connect to database"
            }
    )
    private String errorMessage;

    @Schema(
            description = "List of logical backups"
    )
    private List<LogicalBackupResponse> logicalBackups;

    @Schema(
            description = "List of external databases"
    )
    private List<BackupExternalDatabaseResponse> externalDatabases;

    private String digest;
    //TODO need granular validation for fields
}
