package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class DeleteOrphansRequest {
    @Schema(description = "List of namespaces orhan databases of which is needed to delete", required = true)
    private List<String> namespaces;
    @Schema(description = "confirmation parameter. If this is not passed or false then orhan database will not be deleted and " +
            "response will contain orhan databases that are registered in DbaaS", required = false)
    private Boolean delete;
}
