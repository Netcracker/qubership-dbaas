package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NonNull;

import java.util.Map;

@Data
@Schema(description = "Request model for recreate existing database. The database will have the same settings and classifier as original")
public class RecreateDatabaseRequest {
    @NonNull
    @Schema(required = true, description = "The physical type of logical database. For example mongodb or postgresql")
    private String type;
    @NonNull
    @Schema(required = true, description = "The unique key of existing database. The list of all created databases in a specific namespace can " +
            "be found by by 'List of all databases' API.")
    private Map<String, Object> classifier;
    @NonNull
    @Schema(required = true, description = "Specifies the identificator of physical database where a logical database will be recreated. " +
            "You can get the list of all physical databases by \"List registered physical databases\" API.")
    private String physicalDatabaseId;
}
