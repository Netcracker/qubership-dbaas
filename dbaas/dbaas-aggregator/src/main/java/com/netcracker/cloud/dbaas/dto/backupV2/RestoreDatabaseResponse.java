package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@Schema(description = "Logical database restore details")
public class RestoreDatabaseResponse {
    @Schema(
            description = "Identifier of the restore database",
            examples = {"550e8400-e29b-41d4-a716-446655440000"},
            required = true
    )
    private UUID id;
    @Schema(
            description = "Name of the database",
            examples = {
                    "mydb"
            },
            required = true
    )
    private String name;
    @Schema(
            description = "List of database classifiers. Each classifier is a sorted map of attributes.",
            examples = "[{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}]"
    )
    private List<ClassifierResponse> classifiers;
    @Schema(
            description = "List of database users",
            examples = "[{\"name\":\"username\",\"role\":\"admin\"}"
    )
    private List<User> users;
    @Schema(
            description = "Database settings as a key-value map",
            examples = "{\"key\": \"value\", \"key\": \"value\"}"
    )
    private Map<String, Object> settings;
    @Schema(
            description = "Blue-Green version of database",
            examples = "v2.1.0"
    )
    private String bgVersion;
    @Schema(
            description = "Current state of the restore database",
            required = true,
            implementation = RestoreTaskStatus.class
    )
    private RestoreTaskStatus status;
    @Schema(description = "Duration of the restore operation", examples = "12000")
    private long duration;
    @Schema(
            description = "Path to the restore file in the storage",
            examples = {
                    "/backups/mydb-2025-11-13.bak"
            },
            required = true
    )
    private String path;
    @Schema(description = "Error message if the restore failed", examples = "Restore Not Found")
    private String errorMessage;
    @Schema(description = "Timestamp when the restore was created", examples = "2025-11-13T12:34:56Z")
    private Instant creationTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Database user information")
    public static class User {
        @Schema(description = "Name of the database user", examples = "username")
        String name;
        @Schema(description = "Role of the database user", examples = "admin")
        String role;
    }
}
