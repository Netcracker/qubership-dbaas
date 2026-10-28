package com.netcracker.cloud.dbaas.dto;

import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "The response to obtain physical database information")
public class PhysicalDatabaseInformation {
    @Schema(description = "Physical database identifier. The value belongs to the specific database cluster",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String physicalDatabaseId;
    @Schema(description = "Type of database. Example: MongoDB, PostgreSQL, elasticsearch, etc.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;
    @Schema(description = "Additional information about physical database. It may be a version of database cluster, any labels, and etc.")
    private Map<String, String> labels;
    @Schema(description = "Adapter API version.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ApiVersion apiVersions;
    @Schema(description = "Prohibition or permission of features.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, Boolean> features;
    @Schema(description = "list of supported roles.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> supportedRoles;
    @Schema(description = "Hostname of the read-only replica endpoint")
    private String readOnlyHost;
}
