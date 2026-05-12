package com.netcracker.cloud.dbaas.dto;

import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
public class CleanupMarkedForDropRequest {
    @Schema(description = "A list of namespaces where the marked for drop databases should be cleaned", required = true)
    private List<String> namespaces;
    @Schema(description = "Confirmation parameter. If this is not passed or 'false' then marked for drop databases will not be deleted and " +
            "response will contain marked for drop databases that are registered in DBaaS for the specified namespaces")
    private Boolean delete = false;
    @Schema(description = "If this parameter is 'true', then deletion errors from physical adapters will be ignored")
    private Boolean force = false;
}
