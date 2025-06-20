package org.qubership.cloud.dbaas.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Represents the particular part of the request which led to the validation error")
public class Source {
    @Schema(description = "JSON path to the part of the request which led to the error")
    private String pointer;
    @Schema(description = "Request parameter which led to the error")
    private String parameter;
    @Schema(description = "URI path variable name which led to the error")
    private String pathVariable;

}
