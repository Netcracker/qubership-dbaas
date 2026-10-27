package com.netcracker.cloud.dbaas.entity.dto.backupV2;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Request to create a logical backup")
public record BackupAdapterRequest(
        @Schema(description = "Storage name for the backup") String storageName,
        @Schema(description = "Path of the backup in the storage") String blobPath,
        @Schema(description = "List of databases to back up, each entry is a map with the database name under the databaseName key")
        List<Map<String, String>> databases) {

    /**
     * Example request payload for the {@code POST /backups/backup} operation. Referenced from the adapter contract
     * annotations; not part of the serialized DTO.
     */
    public static final String EXAMPLE_REQUEST = """
            {
              "storageName": "minio",
              "blobPath": "backups/inventory/2026-10-23T12-00-00",
              "databases": [
                { "databaseName": "inventory" },
                { "databaseName": "orders" }
              ]
            }
            """;
}
