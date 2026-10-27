package com.netcracker.cloud.dbaas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Requests database backup daemon to restore specified backup with specified databases info")
public class RestoreRequest {

    /**
     * Example request payload for the {@code POST /backups/{backupId}/restoration} operation. Referenced from the
     * adapter contract annotations; not part of the serialized DTO.
     */
    public static final String EXAMPLE_REQUEST = """
            {
              "regenerateNames": true,
              "databases": [
                {
                  "namespace": "payments",
                  "microservice": "inventory-service",
                  "name": "inventory"
                }
              ]
            }
            """;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "List of databases to restore")
    private List<Database> databases = new ArrayList<>();
    @Schema(description = "Defines if adapter should generate names for each restoring database, and restore databases under new names, " +
            "which would effectively \"clone\" databases from backup. This action MUST NOT affect any of \"source\" databases " +
            "whether they are present in cluster or not.")
    private Boolean regenerateNames = false;

    @Data
    @AllArgsConstructor
    @Schema(description = "Info about database to restore")
    public static class Database {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Namespace name")
        @NonNull
        private String namespace;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Microservice name")
        @NonNull
        private String microservice;
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Database name")
        @NonNull
        private String name;
        private String prefix;

        public Database(@NonNull String namespace, @NonNull String microservice, @NonNull String name) {
            this.namespace = namespace;
            this.microservice = microservice;
            this.name = name;
        }
    }
}
