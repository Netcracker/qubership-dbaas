package org.qubership.cloud.dbaas.dto;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import org.qubership.cloud.dbaas.dto.v3.UserRolesServices;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import lombok.Data;

import java.security.Principal;
import java.util.Map;

@Data
public class ClassifierWithRolesRequest implements UserRolesServices {
    @Schema(description = "Database composite identify key. See details in https://perch.qubership.org/display/CLOUDCORE/DbaaS+Database+Classifier", required = true)
    private Map<String, Object> classifier;

    @Schema(description = "Origin service which send request")
    private String originService;

    @Schema(description = "Indicates connection properties with which user role should be returned to a client")
    private String userRole;

    public String getOriginService() {
        if(originService != null && !originService.isEmpty()) {
            return originService;
        }

        SecurityContext securityContext = CDI.current().select(SecurityContext.class).get();
        Principal defaultPrincipal = securityContext.getUserPrincipal();

        if(!(defaultPrincipal instanceof DefaultJWTCallerPrincipal principal)) {
            return originService;
        }

        Map<String, Object> kubernetesClaims = principal.getClaim("kubernetes.io");

        JsonObject serviceAccount = (JsonObject) kubernetesClaims.get("serviceaccount");
        JsonString serviceAccountName = (JsonString)  serviceAccount.get("name");

        return serviceAccountName.getString();
    }
}
