package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.BackupTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Data
@AllArgsConstructor
@Schema(description = "Logical database backup details")
public class BackupDatabaseResponse {
    @Schema(
            description = "Identifier of the backup database",
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
    private List<SortedMap<String, Object>> classifiers;
    @Schema(
            description = "Database settings as a key-value map",
            examples = "{\"key\": \"value\", \"key\": \"value\"}"
    )
    private Map<String, Object> settings;
    @Schema(
            description = "List of database users",
            examples = "[{\"name\":\"username\",\"role\":\"admin\"}]"
    )
    private List<User> users;
    @Schema(
            description = "Indicates the type of the database",
            examples = "true"
    )
    private boolean configurational;
    @Schema(
            description = "Current state of the backup database",
            required = true,
            implementation = BackupTaskStatus.class)
    private BackupTaskStatus status;
    @Schema(description = "Size of the backup", examples = "12256")
    private long size;
    @Schema(description = "Duration of the backup operation", examples = "12000")
    private long duration;
    @Schema(
            description = "Path to the backup file in the storage",
            examples = {
                    "/backups/mydb-2025-11-13.bak"
            },
            required = true
    )
    private String path;
    @Schema(description = "Error message if the backup failed",  examples = "Backup Not Found")
    private String errorMessage;
    @Schema(description = "Timestamp when the backup was created", examples = "2025-11-13T12:34:56Z")
    private Instant creationTime;

    @Data
    @Builder
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

