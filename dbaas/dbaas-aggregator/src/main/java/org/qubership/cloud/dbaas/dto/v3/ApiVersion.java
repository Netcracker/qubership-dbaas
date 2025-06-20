package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ApiVersion {
    @Schema(description = "The root URL part for that API version")
    public List<Spec> specs;

    @Data
    @AllArgsConstructor
    public static class Spec {
        @Schema(description = "The root URL part for that API version")
        String specRootUrl;
        @Schema(description = "The last adapter API major version")
        Integer major;
        @Schema(description = "The last adapter API minor version")
        Integer minor;
        @Schema(description = "Contains a list of supported adapter API major versions")
        List<Integer> supportedMajors;
    }
}