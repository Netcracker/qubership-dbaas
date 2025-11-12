package com.netcracker.cloud.dbaas.dto.backupV2;

import com.netcracker.cloud.dbaas.DatabaseType;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Single filter criteria for backup and restore operations")
public class Filter {
    @Schema(description = "Filter by Kubernetes namespaces")
    private List<String> namespace = new ArrayList<>();
    @Schema(description = "Filter by microservice names")
    private List<String> microserviceName = new ArrayList<>();
    @Schema(
            description = "Filter by database types",
            implementation = DatabaseType.class,
            type = SchemaType.ARRAY
    )
    private List<DatabaseType> databaseType = new ArrayList<>();
    @Schema(
            description = "Filter by database kinds",
            implementation = DatabaseKind.class,
            type = SchemaType.ARRAY
    )
    private List<DatabaseKind> databaseKind = new ArrayList<>();
}
