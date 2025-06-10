package org.qubership.cloud.dbaas.dto;

import lombok.Data;
import lombok.NonNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Data
@Schema(description = "Request model for linking existing databases to different namespace")
public class LinkDatabasesRequest {
    @NonNull
    @Schema(required = true, description = "The list of microservice names whose databases will be linked to target namespace")
    List<String> serviceNames;

    @NonNull
    @Schema(required = true, description = "Namespace, to which databases will be linked")
    String targetNamespace;
}
