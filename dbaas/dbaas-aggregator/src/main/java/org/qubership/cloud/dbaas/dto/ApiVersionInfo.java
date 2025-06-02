package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ApiVersionInfo {
    @Schema(description = "The last dbaas-aggregator API major version")
    int major;
    @Schema(description = "The lath dbaas-aggregator API minor version")
    int minor;
    @Schema(description = "Contains a list of supported dbaas-aggregator API major version")
    List<Integer> supportedMajors;
    private List<ApiVersionElement> specs;

    public ApiVersionInfo(List<ApiVersionElement> specs) {
        this.specs = specs;
        ApiVersionElement apiElement = specs.stream().filter(apiVersionElement -> apiVersionElement.specRootUrl.equals("/api"))
                .findFirst()
                .get();
        this.major = apiElement.major;
        this.minor = apiElement.minor;
        this.supportedMajors = apiElement.supportedMajors;
    }

    @Data
    public static class ApiVersionElement {
        public ApiVersionElement(String specRootUrl, int major, int minor, List<Integer> supportedMajors) {
            this.specRootUrl = specRootUrl;
            this.major = major;
            this.minor = minor;
            this.supportedMajors = supportedMajors;
        }

        String specRootUrl;
        @Schema(description = "The last dbaas-aggregator API major version")
        int major;
        @Schema(description = "The lath dbaas-aggregator API minor version")
        int minor;
        @Schema(description = "Contains a list of supported dbaas-aggregator API major version")
        List<Integer> supportedMajors;
    }
}
