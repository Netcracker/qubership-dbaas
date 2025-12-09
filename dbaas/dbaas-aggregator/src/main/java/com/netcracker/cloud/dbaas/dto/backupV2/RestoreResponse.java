package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.ExternalDatabaseStrategy;
import com.netcracker.cloud.dbaas.enums.RestoreStatus;
import com.netcracker.cloud.dbaas.utils.validation.group.RestoreGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@AllArgsConstructor
@Schema(description = "Response containing the restore operation details")
public class RestoreResponse {
    @Schema(
            description = "Unique identifier of the restore",
            examples = {
                    "restore-before-prod-update-20251203T1020-4t6S"
            },
            required = true
    )
    private String restoreName;
    @Schema(
            description = "Unique identifier of the backup",
            examples = {
                    "before-prod-update-20251013T1345-G5s8"
            }
    )
    private String backupName;
    @Schema(
            description = "Name of the storage backend containing the restore",
            examples = {
                    "s3-backend"
            }
    )
    private String storageName;
    @Schema(
            description = "Path to the restore file in the storage",
            examples = {
                    "/backups"
            }
    )
    private String blobPath;
    @Schema(
            description = "How to handle external databases during restore",
            examples = {
                    "FAIL"
            },
            implementation = ExternalDatabaseStrategy.class
    )
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy;
    @Schema(
            description = "Criteria used to filter restore operations",
            implementation = FilterCriteria.class
    )
    @Valid
    @ConvertGroup(from = Default.class, to = RestoreGroup.class)
    private FilterCriteria filterCriteria;
    @Schema(
            description = "Mapping configuration for the restore",
            implementation = Mapping.class
    )
    private Mapping mapping;
    @Schema(
            description = "Current state of the restore operation",
            required = true,
            implementation = RestoreStatus.class)
    @NotNull
    private RestoreStatus status;
    @Schema(
            description = "Total number of databases being restored",
            examples = "5"
    )
    private Integer total;
    @Schema(
            description = "Completed databases restore operation",
            examples = "5"
    )
    private Integer completed;
    @Schema(
            description = "Aggregated error messages during restore operation",
            examples = "Backup Not Found"
    )
    private String errorMessage;
    @Schema(description = "Aggregated duration of databases", examples = "1200")
    private Long duration;
    @Schema(description = "Total number of adapter requests", examples = "1")
    private Integer attemptCount;
    @Schema(
            description = "List of logical restores",
            implementation = LogicalRestoreResponse.class,
            type = SchemaType.ARRAY
    )
    private List<LogicalRestoreResponse> logicalRestores;
    @Schema(
            description = "List of external databases",
            implementation = RestoreExternalDatabaseResponse.class,
            type = SchemaType.ARRAY
    )
    private List<RestoreExternalDatabaseResponse> externalDatabases;
}
