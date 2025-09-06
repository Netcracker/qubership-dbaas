package com.netcracker.cloud.dbaas.dto.backupV2;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Data
@NoArgsConstructor
@Schema(description = "Defines how source database configurations map to target configurations during restore")
public class Mapping {
    @Schema(description = "Mapping of source namespace to target namespace", example = "{\"source-namespace\": \"target-namespace\"}")
    private Map<String, String> namespaces;

    @Schema(description = "Mapping of source tenant to target tenant", example = "{\"source-tenant\": \"target-tenant\"}")
    private Map<String, String> tenants;
}
