package com.netcracker.cloud.dbaas.config.security;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.smallrye.jwt.runtime.auth.JWTAuthMechanism;
import io.quarkus.vertx.http.runtime.security.BasicAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

@Priority(1)
@ApplicationScoped
@Slf4j
public class BasicAndK8sJwtAuthMechanism implements HttpAuthenticationMechanism {
    @Inject
    BasicAuthenticationMechanism basicAuth;

    @Inject
    JWTAuthMechanism jwtAuth;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        return selectMechanism(context).authenticate(context, identityProviderManager);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return selectMechanism(context).getChallenge(context);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        Set<Class<? extends AuthenticationRequest>> types = new HashSet<>();
        types.addAll(basicAuth.getCredentialTypes());
        types.addAll(jwtAuth.getCredentialTypes());
        return types;
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return selectMechanism(context).getCredentialTransport(context);
    }

    private HttpAuthenticationMechanism selectMechanism(RoutingContext context) {
        if (isBearerTokenPresent(context)) {
            return jwtAuth;
        } else {
            return basicAuth;
        }
    }

    private boolean isBearerTokenPresent(RoutingContext context) {
        String authHeader = context.request().getHeader("Authorization");
        return authHeader != null && authHeader.startsWith("Bearer ");
    }
}
