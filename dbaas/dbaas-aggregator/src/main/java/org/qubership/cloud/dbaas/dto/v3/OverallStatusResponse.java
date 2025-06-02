package org.qubership.cloud.dbaas.dto.v3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OverallStatusResponse {
    @Schema(description = "Overall health status")
    private String overallHealthStatus;
    @Schema(description = "Number of logical databases")
    private Integer overallLogicalDbNumber;
    @Schema(description = "Information about physical databases")
    private List<PhysicalDatabaseInfo> physicalDatabaseInfoList;
}
