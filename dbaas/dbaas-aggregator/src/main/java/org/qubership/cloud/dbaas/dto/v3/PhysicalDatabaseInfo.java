package org.qubership.cloud.dbaas.dto.v3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhysicalDatabaseInfo {
    @Schema(description = "Physical database identifier")
    private String physicalDatabaseId;
    @Schema(description = "Adapter health status")
    private String healthStatus;
    @Schema(description = "Number of logical databases related to this physical database")
    private String logicalDbNumber;
}
