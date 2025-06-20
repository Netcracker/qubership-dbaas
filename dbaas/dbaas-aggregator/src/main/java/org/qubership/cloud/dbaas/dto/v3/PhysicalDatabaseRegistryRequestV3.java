package org.qubership.cloud.dbaas.dto.v3;

import org.qubership.cloud.dbaas.dto.HttpBasicCredentials;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.util.Map;

@EqualsAndHashCode
@Data
@ToString(callSuper = true)
@Schema(description = "V3 Request model for sending physical database registration to DBaaS")
@NoArgsConstructor(force = true)
@RequiredArgsConstructor
public class PhysicalDatabaseRegistryRequestV3 {
    @Schema(required = true, description = "Physical address of DbaaS adapter. The address is used for CRUD operation with logic databases.")
    private String adapterAddress;
    @Schema(required = true, description = "Basic authentication username and password for requests from DbaaS Aggregator to DbaaS adapter.")
    private HttpBasicCredentials httpBasicCredentials;
    @Schema(description = "Additional information about physical database. It may be a version of database cluster, any labels, and etc.")
    private Map<String, String> labels;
    @Schema(required = true, description = "Information about supported roles, adapter api version and features")
    @NonNull
    private Metadata metadata;
    @Schema(required = true, description = "Adapter status: running or run")
    @NonNull
    private String status;
}
