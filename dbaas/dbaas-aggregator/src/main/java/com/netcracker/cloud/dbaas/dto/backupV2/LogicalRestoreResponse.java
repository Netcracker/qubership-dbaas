package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreStatus;
import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Logical restore details")
public class LogicalRestoreResponse {

    @Schema(
            description = "Unique identifier of the logical restore",
            example = "4e1e9922-e5bf-490d-bb57-18a14de593c6",
            required = true
    )
    private UUID id;

    @Schema(description = "Name of the logical backup in adapter", required = true)
    private String logicalRestoreName;

    @Schema(
            description = "Unique identifier of the adapter",
            example = "4e1e9922-e5bf-490d-bb57-18a14de593c6",
            required = true
    )
    private String adapterId;

    @Schema(
            description = "Type of the adapter",
            example = "postgres",
            required = true
    )
    private String type;

    @Schema(
            description = "List of logical restore databases",
            required = true
    )
    private List<RestoreDatabaseResponse> restoreDatabases;

    @Schema(
            description = "Current state of the backup operation",
            required = true,
            implementation = RestoreStatus.class)
    private RestoreTaskStatus status;

    @Schema(
            description = "Information about error message during restore process"
    )
    private String errorMessage;

    @Schema(
            description = "Aggregated information about creation time of databases in adapter"
    )
    private LocalDateTime creationTime;

    @Schema(
            description = "Aggregated information about completion time of databases in adapter"
    )
    private LocalDateTime completionTime;
}
