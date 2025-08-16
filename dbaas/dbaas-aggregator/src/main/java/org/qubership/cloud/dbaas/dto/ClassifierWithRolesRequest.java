package org.qubership.cloud.dbaas.dto;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.qubership.cloud.dbaas.dto.v3.UserRolesServices;

import java.security.Principal;
import java.util.Map;

@Data
public class ClassifierWithRolesRequest implements UserRolesServices {
    @Inject
    SecurityContext securityContext;
    @Schema(description = "Database composite identify key. See details in https://perch.qubership.org/display/CLOUDCORE/DbaaS+Database+Classifier", required = true)
    private Map<String, Object> classifier;
    @Schema(description = "Origin service which send request")
    private String originService;
    @Schema(description = "Indicates connection properties with which user role should be returned to a client")
    private String userRole;
}
