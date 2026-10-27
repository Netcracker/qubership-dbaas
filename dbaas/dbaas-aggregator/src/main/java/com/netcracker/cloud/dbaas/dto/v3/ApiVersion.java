package com.netcracker.cloud.dbaas.dto.v3;

import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
@Schema(description = "The adapter API version")
@io.swagger.v3.oas.annotations.media.Schema(description = "The adapter API version")
public class ApiVersion {
    @Schema(description = "List of supported API version specification", required = true)
    @io.swagger.v3.oas.annotations.media.Schema(description = "List of supported API version specification",
            requiredMode = RequiredMode.REQUIRED)
    public List<Spec> specs;

    @Data
    @AllArgsConstructor
    @Schema(name = "ApiVersionSpec", description = "The adapter API version spec")
    @io.swagger.v3.oas.annotations.media.Schema(name = "ApiVersionSpec", description = "The adapter API version spec")
    public static class Spec {
        @Schema(description = "The root URL part for that API version", required = true)
        @io.swagger.v3.oas.annotations.media.Schema(description = "The root URL part for that API version",
                requiredMode = RequiredMode.REQUIRED)
        String specRootUrl;
        @Schema(description = "The last adapter API major version", required = true)
        @io.swagger.v3.oas.annotations.media.Schema(description = "The last adapter API major version",
                requiredMode = RequiredMode.REQUIRED)
        Integer major;
        @Schema(description = "The last adapter API minor version", required = true)
        @io.swagger.v3.oas.annotations.media.Schema(description = "The last adapter API minor version",
                requiredMode = RequiredMode.REQUIRED)
        Integer minor;
        @Schema(description = "Contains a list of supported adapter API major versions", required = true)
        @io.swagger.v3.oas.annotations.media.Schema(description = "Contains a list of supported adapter API major versions",
                requiredMode = RequiredMode.REQUIRED)
        List<Integer> supportedMajors;
    }
}
