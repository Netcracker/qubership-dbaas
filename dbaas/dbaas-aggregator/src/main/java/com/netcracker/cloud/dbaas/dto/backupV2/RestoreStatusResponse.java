package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@AllArgsConstructor
@Schema(description = "Current status of a restore operation")
public class RestoreStatusResponse {
    @Schema(
            description = "Current state of the restore operation",
            required = true,
            implementation = RestoreStatus.class)
    private RestoreStatus status;

    @Schema(
            description = "Total number of databases to be restored",
            example = "5",
            minimum = "0"
    )
    private Integer total;

    @Schema(
            description = "Number of databases successfully restored",
            example = "3",
            minimum = "0"
    )
    private Integer completed;

    @Schema(
            description = "Error details if the restore failed",
            example = "Insufficient permissions to create database"
    )
    private String errorMessage;
}
