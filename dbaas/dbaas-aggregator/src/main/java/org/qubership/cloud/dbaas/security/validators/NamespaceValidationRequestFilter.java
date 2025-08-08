package org.qubership.cloud.dbaas.security.validators;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.dbaas.DbaasApiPath;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@Provider
@Slf4j
public class NamespaceValidationRequestFilter implements ContainerRequestFilter {
    @Inject
    NamespaceValidator namespaceValidator;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        Principal defaultPrincipal = requestContext.getSecurityContext().getUserPrincipal();

        if (!(defaultPrincipal instanceof DefaultJWTCallerPrincipal principal)) {
            return;
        }

        String namespaceFromPath = requestContext.getUriInfo().getPathParameters().getFirst(DbaasApiPath.NAMESPACE_PARAMETER);

        // Don't check namespace if not present
        if (namespaceFromPath == null) {
            return;
        }

        Map<String, Object> kubernetesClaims = principal.getClaim("kubernetes.io");
        JsonString namespaceFromJwt = (JsonString) kubernetesClaims.get("namespace");

        if (!namespaceValidator.checkNamespaceIsolation(namespaceFromPath, namespaceFromJwt.getString())) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN.getStatusCode(), "Namespace from path and namespace from jwt token doesn't not match or aren't in the same composite structure").build());
        }
    }
}
