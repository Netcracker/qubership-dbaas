package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Request to restore a logical backup")
public record RestoreAdapterRequest(
        @Schema(description = "Storage name of the backup") String storageName,
        @Schema(description = "Path of the backup in the storage") String blobPath,
        @Schema(description = "List of databases to restore, each entry is a map with the database name under the databaseName key")
        List<Map<String, String>> databases) {

    public static final String EXAMPLE_REQUEST = """
            {
              "storageName": "minio",
              "blobPath": "backups/inventory/2026-10-23T12-00-00",
              "databases": [
                {
                  "microserviceName": "inventory-service",
                  "databaseName": "inventory",
                  "namespace": "payments"
                }
              ]
            }
            """;
}
