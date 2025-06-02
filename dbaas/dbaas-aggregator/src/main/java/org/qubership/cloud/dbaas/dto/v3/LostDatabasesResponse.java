package org.qubership.cloud.dbaas.dto.v3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LostDatabasesResponse {
    @Schema(required = true, description = "Physical database identificator.")
    private String physicalDatabaseId;
    @Schema(required = true, description = "Lost databases related to adapter with this physical database id.")
    private List<DatabaseResponseV3ListCP> databases;
    @Schema(required = false, description = "Error message from adapter.")
    private String errorMessage;
}
