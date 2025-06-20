package org.qubership.cloud.dbaas.dto;

import org.qubership.cloud.dbaas.dto.v3.UserRolesServices;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
public class ClassifierWithRolesRequest implements UserRolesServices {
    @Schema(description = "Database composite identify key. See details in https://perch.qubership.org/display/CLOUDCORE/DbaaS+Database+Classifier", required = true)
    private Map<String, Object> classifier;

    @Schema(description = "Origin service which send request", required = true)
    private String originService;

    @Schema(description = "Indicates connection properties with which user role should be returned to a client")
    private String userRole;
}
