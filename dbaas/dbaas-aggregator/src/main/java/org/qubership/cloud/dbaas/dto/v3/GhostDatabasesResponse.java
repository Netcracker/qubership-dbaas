package org.qubership.cloud.dbaas.dto.v3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GhostDatabasesResponse {
    @Schema(required = true, description = "Physical database identificator.")
    private String physicalDatabaseId;
    @Schema(required = true, description = "Ghost databases names related to adapter with this physical databases id.")
    private List<String> dbNames;
    @Schema(required = false, description = "Error message from adapter.")
    private String errorMessage;
}
