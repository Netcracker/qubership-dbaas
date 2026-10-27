package com.netcracker.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "Contains current and new settings")
@io.swagger.v3.oas.annotations.media.Schema(description = "Contains current and new settings")
public class UpdateSettingsAdapterRequest {

    /**
     * Example request payload for the {@code PUT /databases/{dbName}/settings} operation. Referenced from the
     * adapter contract annotations; not part of the serialized DTO. The settings themselves are database-specific,
     * the keys shown are illustrative PostgreSQL-like GUCs.
     */
    public static final String EXAMPLE_REQUEST = """
            {
              "currentSettings": {
                "max_connections": 100,
                "search_path": [ "public" ]
              },
              "newSettings": {
                "max_connections": 200,
                "search_path": [ "public", "inventory" ]
              }
            }
            """;

    @Schema(required = true, description = "All current database settings already applied to the target database.")
    @io.swagger.v3.oas.annotations.media.Schema(required = true, description = "All current database settings already applied to the target database.")
    private Map<String, Object> currentSettings;
    @Schema(required = true, description = "New version of all database settings to apply to the database. Must consist of all settings the target database must incorporate after this update request will be executed.")
    @io.swagger.v3.oas.annotations.media.Schema(required = true, description = "New version of all database settings to apply to the database. " +
            "Must consist of all settings the target database must incorporate after this update request will be executed.")
    private Map<String, Object> newSettings;

}
