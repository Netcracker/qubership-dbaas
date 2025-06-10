package org.qubership.cloud.dbaas.dto.v3;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Data
@Schema(description = "Request object containing the necessary information to update the physical host in a logical database")
@NoArgsConstructor
public class UpdateHostRequest {

    @NonNull
    @Schema(required = true, description = "The physical type of logical database. For example mongodb or postgresql")
    private String type;

    @NonNull
    @Schema(required = true, description = "The unique key of existing database. ")
    private Map<String, Object> classifier;

    @Schema(required = false,
            description = "If true, a copy of the logical database registry will be created before applying changes. "
                    + "If false, changes will be made directly to the existing registry.", defaultValue = "true")
    private Boolean makeCopy = Boolean.TRUE;

    @Schema(required = true,
            description = "Contains physical database host on which it's needed to change. " +
                    "The host must follow the format: <physical database k8s service>.<physical database namespace>. " +
                    "Example: pg-patroni.core-postgresql.")
    private String physicalDatabaseHost;

    @Schema(required = true,
            description = "Specifies id of new physical database. The value can be found in dbaas-adapter env")
    private String physicalDatabaseId;

}
