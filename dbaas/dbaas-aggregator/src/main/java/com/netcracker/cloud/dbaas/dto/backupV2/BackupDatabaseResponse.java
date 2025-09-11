package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

@Data
@AllArgsConstructor
@Schema(description = "Logical database backup details")
public class BackupDatabaseResponse {

    @Schema(description = "Name of the database", example = "mydb", required = true)
    private String name;
    @Schema(description = "List of database classifiers")
    private List<SortedMap<String, Object>> classifiers;
    @Schema(description = "List of database settings")
    private Map<String, Object> settings;
    @Schema(description = "List of database users")
    private List<User> users;
    @Schema(description = "List of database resources")
    private Map<String, Object> resources;

    private Status status;

    private long size;

    private long duration;

    private String path;

    private String errorMessage;

    private LocalDateTime creationTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}

