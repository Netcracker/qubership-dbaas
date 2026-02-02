package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

@Data
@NoArgsConstructor
@Schema(description = "External database details")
public class BackupExternalDatabaseResponse {
    @Schema(
            description = "Identifier of the external database",
            examples = {"550e8400-e29b-41d4-a716-446655440000"},
            required = true
    )
    private UUID id;
    @Schema(description = "Name of the external database", examples = "mydb", required = true)
    private String name;
    @Schema(
            description = "Type of the database",
            examples = "postgresql"
    )
    private String type;
    @Schema(
            description = "List of database classifiers. Each classifier is a sorted map of attributes.",
            examples = "[{\"namespace\":\"namespace\", \"microserviceName\":\"microserviceName\", \"scope\":\"service\"}]"
    )
    private List<SortedMap<String, Object>> classifiers;
}
