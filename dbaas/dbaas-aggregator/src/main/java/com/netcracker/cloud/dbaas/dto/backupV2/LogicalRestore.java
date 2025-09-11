package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Logical restore details")
public class LogicalRestore {

    @Schema(
            description = "Unique identifier of the logical restore",
            example = "4e1e9922-e5bf-490d-bb57-18a14de593c6",
            required = true
    )
    private String id;

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
            description = "Status of the logical restore (arbitrary key-value pairs)",
            example = "{\"phase\": \"running\", \"progress\": 50}"
    )
    private Map<String, Object> status;

    @Schema(
            description = "List of logical restore databases",
            required = true
    )
    private List<LogicalRestoreDatabase> logicalRestoreDatabases;
}
