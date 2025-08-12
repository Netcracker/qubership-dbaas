package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "Single filter criteria for backup and restore operations")
public class Filter {
    @Schema(description = "Filter by Kubernetes namespaces")
    private List<String> namespace;
    @Schema(description = "Filter by microservice names")
    private List<String> microserviceName;
    @Schema(description = "Filter by database types")
    private List<DatabaseType> databaseType;
    @Schema(description = "Filter by database kinds")
    private List<DatabaseKind> databaseKind;
}
