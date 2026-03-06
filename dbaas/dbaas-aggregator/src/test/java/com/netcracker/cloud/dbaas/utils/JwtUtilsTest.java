package com.netcracker.cloud.dbaas.utils;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class JwtUtilsTest {

    @Test
    void testGetServiceAccountName() {
        JsonWebToken token = mock(JsonWebToken.class);

        JsonObject serviceAccount = Json.createObjectBuilder()
                .add("name", "test-service-account")
                .build();

        Map<String, Object> kubernetesClaims = new HashMap<>();
        kubernetesClaims.put("serviceaccount", serviceAccount);

        when(token.getClaim("kubernetes.io")).thenReturn(kubernetesClaims);

        String result = JwtUtils.getServiceAccountName(token);

        assertEquals("test-service-account", result);
        verify(token, times(1)).getClaim("kubernetes.io");
    }

    @Test
    void testGetNamespace_withValidPrincipal() {
        SecurityContext securityContext = mock(SecurityContext.class);
        DefaultJWTCallerPrincipal principal = mock(DefaultJWTCallerPrincipal.class);

        JsonString namespace = Json.createValue("test-namespace");
        Map<String, Object> kubernetesClaims = new HashMap<>();
        kubernetesClaims.put("namespace", namespace);

        when(principal.getClaim("kubernetes.io")).thenReturn(kubernetesClaims);
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        String result = JwtUtils.getNamespace(securityContext);

        assertEquals("test-namespace", result);
        verify(principal, times(1)).getClaim("kubernetes.io");
    }

    @Test
    void testGetNamespace_withInvalidPrincipal() {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUserPrincipal()).thenReturn(() -> "someUser");

        String result = JwtUtils.getNamespace(securityContext);

        assertEquals("", result);
    }
}
