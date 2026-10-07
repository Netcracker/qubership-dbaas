package com.netcracker.cloud.dbaas.dto.composite;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.*;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Set;

@Data
@AllArgsConstructor
@Builder
public class CompositeStructureDto {

    @Schema(description = "Composite identifier. Usually it's baseline or origin baseline in blue-green scheme", required = true)
    @NonNull
    private String id;

    @Schema(description = "Namespaces that are included in composite structure (baseline and satellites)", required = true)
    @NonNull
    private Set<String> namespaces;

    @Schema(description = "Index of composite structure (changes on each composite struct modification)", required = true)
    @PositiveOrZero
    private Long modifyIndex;
}
