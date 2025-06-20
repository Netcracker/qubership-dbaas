package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
@Schema
public class ValidateRulesResponse {
    @Schema(required = true, description = "Map with pairs of 'label : corresponding physical database'.")
    private Map<String, String> mapLabelToPhysicalDb;

    @Schema(required = true, description = "Default physical databases for each registered type.")
    private Map<String, String> defaultPhysicalDatabases;
}
