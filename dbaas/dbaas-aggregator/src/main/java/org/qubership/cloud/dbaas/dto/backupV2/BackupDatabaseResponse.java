package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
    @Schema(description = "Whether the database is externally managed", example = "false")
    private boolean externallyManaged;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        String name;
        String role;
    }
}

