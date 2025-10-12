package com.netcracker.cloud.dbaas.security.validators;

import com.netcracker.cloud.dbaas.DbaasApiPath;
import com.netcracker.cloud.dbaas.utils.JwtUtils;
import io.smallrye.jwt.auth.principal.JWTCallerPrincipal;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.Mockito.*;

class NamespaceValidationRequestFilterTest {

    private NamespaceValidationRequestFilter filter;
    private NamespaceValidator namespaceValidator;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> pathParams;
    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        filter = new NamespaceValidationRequestFilter();
        namespaceValidator = mock(NamespaceValidator.class);
        filter.namespaceValidator = namespaceValidator;

        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        pathParams = mock(MultivaluedMap.class);
        securityContext = mock(SecurityContext.class);

        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPathParameters()).thenReturn(pathParams);
        when(requestContext.getSecurityContext()).thenReturn(securityContext);
    }

    @Test
    void testFilter_noNamespaceInPath() throws IOException {
        when(pathParams.getFirst(DbaasApiPath.NAMESPACE_PARAMETER)).thenReturn(null);

        filter.filter(requestContext);

        verify(namespaceValidator, never()).checkNamespaceIsolation(any(), any());
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void testFilter_notJwtPrincipal() throws IOException {
        when(pathParams.getFirst(DbaasApiPath.NAMESPACE_PARAMETER)).thenReturn("ns1");
        when(securityContext.getUserPrincipal()).thenReturn(() -> "basicUser");

        filter.filter(requestContext);

        verify(namespaceValidator, never()).checkNamespaceIsolation(any(), any());
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void testFilter_validNamespaceIsolation() throws IOException {
        String nsFromPath = "ns1";
        when(pathParams.getFirst(DbaasApiPath.NAMESPACE_PARAMETER)).thenReturn(nsFromPath);

        JWTCallerPrincipal jwtPrincipal = mock(JWTCallerPrincipal.class);
        when(securityContext.getUserPrincipal()).thenReturn(jwtPrincipal);

        try (var mocked = mockStatic(JwtUtils.class)) {
            mocked.when(() -> JwtUtils.getNamespace(securityContext)).thenReturn("ns1");

            when(namespaceValidator.checkNamespaceIsolation("ns1", "ns1")).thenReturn(true);

            filter.filter(requestContext);

            verify(namespaceValidator, times(1)).checkNamespaceIsolation("ns1", "ns1");
            verify(requestContext, never()).abortWith(any());
        }
    }

    @Test
    void testFilter_invalidNamespaceIsolation() throws IOException {
        String nsFromPath = "ns1";
        when(pathParams.getFirst(DbaasApiPath.NAMESPACE_PARAMETER)).thenReturn(nsFromPath);

        JWTCallerPrincipal jwtPrincipal = mock(JWTCallerPrincipal.class);
        when(securityContext.getUserPrincipal()).thenReturn(jwtPrincipal);

        try (var mocked = mockStatic(JwtUtils.class)) {
            mocked.when(() -> JwtUtils.getNamespace(securityContext)).thenReturn("other-ns");

            when(namespaceValidator.checkNamespaceIsolation("ns1", "other-ns")).thenReturn(false);

            filter.filter(requestContext);

            verify(namespaceValidator, times(1)).checkNamespaceIsolation("ns1", "other-ns");
            verify(requestContext, times(1)).abortWith(any(Response.class));
        }
    }
}
