package com.netcracker.cloud.dbaas.config.security;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.smallrye.jwt.runtime.auth.JWTAuthMechanism;
import io.quarkus.vertx.http.runtime.security.BasicAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BasicAndKubernetesAuthMechanismTest {

    private BasicAndKubernetesAuthMechanism mechanism;
    private BasicAuthenticationMechanism basicAuth;
    private JWTAuthMechanism jwtAuth;
    private RoutingContext context;
    private HttpServerRequest request;
    private IdentityProviderManager idManager;

    @BeforeEach
    void setUp() {
        basicAuth = mock(BasicAuthenticationMechanism.class);
        jwtAuth = mock(JWTAuthMechanism.class);

        Set<Class<? extends AuthenticationRequest>> basicTypes =
                new HashSet<>(Collections.singleton(AuthenticationRequest.class));
        Set<Class<? extends AuthenticationRequest>> jwtTypes =
                new HashSet<>(Collections.singleton(AuthenticationRequest.class));
        when(basicAuth.getCredentialTypes()).thenReturn(basicTypes);
        when(jwtAuth.getCredentialTypes()).thenReturn(jwtTypes);

        mechanism = new BasicAndKubernetesAuthMechanism(basicAuth, jwtAuth);

        context = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        idManager = mock(IdentityProviderManager.class);
    }

    @Test
    void testAuthenticate_withBearerToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        Uni<SecurityIdentity> expected = Uni.createFrom().nullItem();
        when(jwtAuth.authenticate(any(), any())).thenReturn(expected);

        Uni<SecurityIdentity> result = mechanism.authenticate(context, idManager);

        verify(jwtAuth, times(1)).authenticate(any(), any());
        verify(basicAuth, never()).authenticate(any(), any());
        assertEquals(expected, result);
    }

    @Test
    void testAuthenticate_withBasicToken() {
        when(request.getHeader("Authorization")).thenReturn("Basic abc");
        Uni<SecurityIdentity> expected = Uni.createFrom().nullItem();
        when(basicAuth.authenticate(any(), any())).thenReturn(expected);

        Uni<SecurityIdentity> result = mechanism.authenticate(context, idManager);

        verify(basicAuth, times(1)).authenticate(any(), any());
        verify(jwtAuth, never()).authenticate(any(), any());
        assertEquals(expected, result);
    }

    @Test
    void testGetChallenge_withBearerToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        Uni<ChallengeData> expected = Uni.createFrom().nullItem();
        when(jwtAuth.getChallenge(any())).thenReturn(expected);

        Uni<ChallengeData> result = mechanism.getChallenge(context);

        verify(jwtAuth, times(1)).getChallenge(any());
        assertEquals(expected, result);
    }

    @Test
    void testGetChallenge_withBasicAuth() {
        when(request.getHeader("Authorization")).thenReturn("Basic abc");
        Uni<ChallengeData> expected = Uni.createFrom().nullItem();
        when(basicAuth.getChallenge(any())).thenReturn(expected);

        Uni<ChallengeData> result = mechanism.getChallenge(context);

        verify(basicAuth, times(1)).getChallenge(any());
        assertEquals(expected, result);
    }

    @Test
    void testGetCredentialTypes() {
        Set<Class<? extends AuthenticationRequest>> result = mechanism.getCredentialTypes();

        assertNotNull(result);
        assertTrue(result.containsAll(basicAuth.getCredentialTypes()));
        assertTrue(result.containsAll(jwtAuth.getCredentialTypes()));

        verify(basicAuth, times(2)).getCredentialTypes();
        verify(jwtAuth, times(2)).getCredentialTypes();
    }

    @Test
    void testGetCredentialTransport_withBearerToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        Uni<HttpCredentialTransport> expected = Uni.createFrom().nullItem();
        when(jwtAuth.getCredentialTransport(any())).thenReturn(expected);

        Uni<HttpCredentialTransport> result = mechanism.getCredentialTransport(context);

        verify(jwtAuth, times(1)).getCredentialTransport(any());
        assertEquals(expected, result);
    }

    @Test
    void testGetCredentialTransport_withBasicAuth() {
        when(request.getHeader("Authorization")).thenReturn("Basic abc");
        Uni<HttpCredentialTransport> expected = Uni.createFrom().nullItem();
        when(basicAuth.getCredentialTransport(any())).thenReturn(expected);

        Uni<HttpCredentialTransport> result = mechanism.getCredentialTransport(context);

        verify(basicAuth, times(1)).getCredentialTransport(any());
        assertEquals(expected, result);
    }

    @Test
    void testIsBearerTokenPresent_privateMethodCoverage() throws Exception {
        // Reflectively test private method
        var method = BasicAndKubernetesAuthMechanism.class.getDeclaredMethod("isBearerTokenPresent", RoutingContext.class);
        method.setAccessible(true);

        when(request.getHeader("Authorization")).thenReturn("Bearer something");
        assertTrue((Boolean) method.invoke(mechanism, context));

        when(request.getHeader("Authorization")).thenReturn("Basic something");
        assertFalse((Boolean) method.invoke(mechanism, context));

        when(request.getHeader("Authorization")).thenReturn(null);
        assertFalse((Boolean) method.invoke(mechanism, context));
    }
}
