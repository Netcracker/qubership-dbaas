package org.qubership.cloud.dbaas.dto.v3;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Metadata {
    @Schema(required = true, description = "Adapter API version")
    @NonNull
    private String apiVersion;

    @Schema(required = false, description = "Adapter API version")
    private ApiVersion apiVersions;

    @Schema(required = true, description = "list of supported roles")
    @NonNull
    private List<String> supportedRoles;

    @Schema(required = true, description = "Prohibition or permission of features")
    @NonNull
    private Map<String, Boolean> features;

    @Schema(required = false, description = "Host of RO pod")
    private String roHost;

    public Metadata(@NonNull String apiVersion, @NonNull List<String> supportedRoles, @NonNull Map<String, Boolean> features) {
        this.apiVersion = apiVersion;
        this.supportedRoles = supportedRoles;
        this.features = features;
    }
}


