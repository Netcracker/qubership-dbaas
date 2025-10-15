package com.netcracker.cloud.dbaas.security.validators;

import com.netcracker.cloud.dbaas.DbaasApiPath;
import com.netcracker.cloud.dbaas.utils.JwtUtils;
import io.smallrye.jwt.auth.principal.JWTCallerPrincipal;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Provider
@Slf4j
public class NamespaceValidationRequestFilter implements ContainerRequestFilter {
    @Inject
    NamespaceValidator namespaceValidator;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String namespaceFromPath = requestContext.getUriInfo().getPathParameters().getFirst(DbaasApiPath.NAMESPACE_PARAMETER);
        // Don't check namespace if not present or not token auth
        if (namespaceFromPath == null || !(requestContext.getSecurityContext().getUserPrincipal() instanceof JWTCallerPrincipal)) {
            return;
        }
        if (!namespaceValidator.checkNamespaceIsolation(namespaceFromPath, JwtUtils.getNamespace(requestContext.getSecurityContext()))) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN.getStatusCode(), "Namespace from path and namespace from jwt token doesn't not match or aren't in the same composite structure").build());
        }
    }
}
