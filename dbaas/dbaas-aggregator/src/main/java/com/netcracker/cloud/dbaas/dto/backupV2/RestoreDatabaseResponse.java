package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.Status;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@Schema(description = "Logical database restore details")
public class RestoreDatabaseResponse {

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
    private List<Map<String, Object>> classifiers;

    @Schema(
            description = "List of database users"
    )
    private List<User> users;

    @Schema(
            description = "Map of database settings"
    )
    private Map<String, Object> settings;

    @Schema(
            description = "Map of database resources"
    )
    private Map<String, Object> resources;

    @Schema(
            description = "Blue-Green version of database"
    )
    private String bgVersion;

    @Schema(
            description = "Restore operation status"
    )
    private Status status;

    @Schema(
            description = "Duration of restore request"
    )
    private long duration;

    @Schema(
            description = "Information about path of database data in compatible storage"
    )
    private String path;

    @Schema(
            description = "Information about error during restore operation"
    )
    private String errorMessage;

    @Schema(
            description = "Information about creation time of database in adapter"
    )
    private LocalDateTime creationTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}
