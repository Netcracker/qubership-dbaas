package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import io.quarkus.security.AuthenticationFailedException;
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
public class BasicAndKubernetesAuthMechanism implements HttpAuthenticationMechanism {
    private static final String BEARER_PREFIX = "Bearer ";

    BasicAuthenticationMechanism basicAuth;
    JWTAuthMechanism jwtAuth;
    boolean kubernetesTokenAccepted;
    Set<Class<? extends AuthenticationRequest>> credentialTypes;

    @Inject
    public BasicAndKubernetesAuthMechanism(BasicAuthenticationMechanism basicAuth, JWTAuthMechanism jwtAuth,
                                           M2MAuthMode m2mAuthMode) {
        this.basicAuth = basicAuth;
        this.jwtAuth = jwtAuth;
        this.kubernetesTokenAccepted = switch (m2mAuthMode) {
            case LEGACY -> false;
            case HYBRID, K8S -> true;
        };

        Set<Class<? extends AuthenticationRequest>> credentialTypes = new HashSet<>();
        credentialTypes.addAll(basicAuth.getCredentialTypes());
        credentialTypes.addAll(jwtAuth.getCredentialTypes());
        this.credentialTypes = credentialTypes;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        boolean bearerPresent = isBearerTokenPresent(context);
        if (bearerPresent && !kubernetesTokenAccepted) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Bearer tokens are not accepted: M2M_AUTH_MODE is legacy"));
        }
        return selectMechanism(bearerPresent).authenticate(context, identityProviderManager);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return selectMechanism(isBearerTokenPresent(context) && kubernetesTokenAccepted).getChallenge(context);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return credentialTypes;
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return selectMechanism(isBearerTokenPresent(context) && kubernetesTokenAccepted).getCredentialTransport(context);
    }

    private HttpAuthenticationMechanism selectMechanism(boolean useJwt) {
        return useJwt ? jwtAuth : basicAuth;
    }

    private boolean isBearerTokenPresent(RoutingContext context) {
        String authHeader = context.request().getHeader("Authorization");
        return authHeader != null && authHeader.startsWith(BEARER_PREFIX);
    }
}
