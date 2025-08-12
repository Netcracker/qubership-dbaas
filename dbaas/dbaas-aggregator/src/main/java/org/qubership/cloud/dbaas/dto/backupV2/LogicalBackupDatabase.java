package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Data
@AllArgsConstructor
@Schema(description = "Logical database backup details")
public class LogicalBackupDatabase {
    @Schema(description = "Unique identifier of the logical backup", example = "4e1e9922-e5bf-490d-bb57-18a14de593c6", required = true)
    private String id;
    @Schema(description = "Name of the database", example = "mydb", required = true)
    private String name;
    @Schema(description = "List of database classifiers")
    private Map<String, Object> classifiers;
    @Schema(description = "List of database settings")
    private Map<String, Object> settings;
    @Schema(description = "List of database users")
    private Map<String, Object> users;
    @Schema(description = "List of database resources")
    private Map<String, Object> resources;
    @Schema(description = "Whether the database is externally managed", example = "false")
    private boolean externallyManaged;

}
