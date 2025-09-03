package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Data
@NoArgsConstructor
@Schema(description = "Logical database restore details")
public class LogicalRestoreDatabase {

    @Schema(
            description = "Unique identifier of the logical restore",
            example = "4e1e9922-e5bf-490d-bb57-18a14de593c6",
            required = true
    )
    private String id;

    @Schema(
            description = "Unique identifier of the logical backup",
            example = "4e1e9922-e5bf-490d-bb57-18a14de593c6",
            required = true
    )
    private String logicalBackupId;

    @Schema(
            description = "Name of the database",
            example = "mydb",
            required = true
    )
    private String name;

    @Schema(
            description = "Database classifier"
    )
    private Map<String, Object> classifier;

    @Schema(
            description = "List of database users"
    )
    private Map<String, Object> users;
}
