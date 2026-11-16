package com.netcracker.cloud.dbaas.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Data
public class DeleteAllInNamespacesRequest {
    @NotEmpty
    @Schema(description = "Namespaces to clean. The operation drops all matching entities in these cloud projects", required = true)
    private Set<String> namespaces;
}
