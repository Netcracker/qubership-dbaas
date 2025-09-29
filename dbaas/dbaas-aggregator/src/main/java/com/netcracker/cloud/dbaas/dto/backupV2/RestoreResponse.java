package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@AllArgsConstructor
@Schema(description = "Response containing the restore operation details")
public class RestoreResponse {
    @Schema(
            description = "Unique identifier for the restore operation",
            example = "restore-before-prod-update-20251203T1020-4t6S",
            required = true
    )
    private String restoreName;

    @Schema(
            description = "Unique identifier of the backup name",
            example = "before-prod-update-20251013T1345-G5s8"
    )
    private String backupName;

    @Schema(
            description = "Name of the storage backend containing the backup",
            example = "s3-backend"
    )
    private String storageName;

    @Schema(
            description = "Path to the backup file in the storage",
            example = "/backups"
    )
    private String blobPath;

    @Schema(
            description = "Criteria used to filter restore operations"
    )
    private FilterCriteria filterCriteria;

    @Schema(
            description = "Mapping configuration for the restore"
    )
    private Mapping mapping;

    @Schema(
            description = "Status of the restore operation (arbitrary key-value pairs)",
            example = "{\"phase\": \"completed\", \"duration\": \"5m\"}"
    )
    private Status status;

    @Schema(
            description = "List of logical restores"
    )
    private List<LogicalRestoreResponse> logicalRestores;
}
