package org.qubership.cloud.dbaas.dto.composite;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;
import lombok.NonNull;

import java.util.Set;

@Data
public class CompositeStructureDto {

    @Schema(description = "Composite identifier. Usually it's baseline or origin baseline in blue-green scheme", required = true)
    @NonNull
    private String id;

    @Schema(description = "Namespaces that are included in composite structure (baseline and satellites)", required = true)
    @NonNull
    private Set<String> namespaces;
}
