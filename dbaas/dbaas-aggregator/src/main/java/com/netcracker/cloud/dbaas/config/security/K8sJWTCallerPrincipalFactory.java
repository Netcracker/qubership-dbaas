package com.netcracker.cloud.dbaas.config.security;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.jwt.auth.principal.*;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.TimeoutExceededException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.lang.JoseException;
import com.netcracker.cloud.dbaas.rest.K8sOidcRestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@ApplicationScoped
@Alternative
@Priority(1)
@Slf4j
public class K8sJWTCallerPrincipalFactory extends JWTCallerPrincipalFactory {
    private static final RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
            .withMaxRetries(5)
            .withBackoff(500, Duration.ofSeconds(60).toMillis(), ChronoUnit.MILLIS);

    private final Object lock = new Object();
    private String jwtJwksEndpoint;
    private JwtConsumer jwtClaimsParser;
    private List<JsonWebKey> jwksCache;

    private K8sOidcRestClient k8sOidcRestClient;

    public K8sJWTCallerPrincipalFactory(
            @ConfigProperty(name = "dbaas.security.k8s.jwt.enabled") boolean isJwtEnabled,
            @ConfigProperty(name = "dbaas.security.k8s.jwt.audience") String jwtAudience,
            K8sOidcRestClient k8sOidcRestClient
    ) {
        if (!isJwtEnabled) {
            return;
        }

        this.k8sOidcRestClient = k8sOidcRestClient;
        log.debug("Started creating K8sJWTCallerPrincipalFactory bean");

        jwtClaimsParser = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setAllowedClockSkewInSeconds(30)
                .setRequireSubject()
                .setExpectedIssuer(k8sOidcRestClient.getJwtIssuer())
                .setExpectedAudience(jwtAudience)
                .setSkipSignatureVerification()
                .build();

        jwtJwksEndpoint = k8sOidcRestClient.getOidcConfiguration().getJwks_uri();

        refreshJwksCache();

        log.debug("Finished creating K8sJWTCallerPrincipalFactory bean");
    }

    @Override
    public JWTCallerPrincipal parse(String token, JWTAuthContextInfo authContextInfo) throws ParseException {
        try {
            JwtContext jwtContext = jwtClaimsParser.process(token);

            if (!verifySignature(token, jwtContext)) {
                throw new ParseException("invalid jwt signature");
            }

            return new DefaultJWTCallerPrincipal(jwtContext.getJwtClaims());
        } catch (InvalidJwtException | JoseException e) {
            throw new ParseException(e.getMessage());
        }
    }

    private boolean verifySignature(String token, JwtContext jwtContext) throws JoseException, ParseException {
        String keyId = jwtContext.getJoseObjects().getFirst().getKeyIdHeaderValue();
        JsonWebKey jsonWebKey = getJwk(keyId);

        if (jsonWebKey == null) {
            throw new ParseException("jwk not found");
        }

        JsonWebSignature jws = new JsonWebSignature();

        jws.setCompactSerialization(token);
        jws.setKey(jsonWebKey.getKey());

        return jws.verifySignature();
    }

    private void refreshJwksCache() {
        try {
            Failsafe.with(retryPolicy).run(() -> {
                String rawJwks = k8sOidcRestClient.getJwks(jwtJwksEndpoint);
                jwksCache = new JsonWebKeySet(rawJwks).getJsonWebKeys();
            });
        } catch (TimeoutExceededException e) {
            log.error("Getting Json web keys from kubernetes jwks endpoint %s failed".formatted(jwtJwksEndpoint), e);
        }
    }

    private JsonWebKey getJwk(String keyId) {
        JsonWebKey jwk = getJwksFromCache(keyId);
        if (jwk != null) {
            return jwk;
        }

        synchronized (lock) {
            JsonWebKey jwksFromCache = getJwksFromCache(keyId);
            if (jwksFromCache != null) {
                return jwksFromCache;
            }

            refreshJwksCache();
        }

        return getJwksFromCache(keyId);
    }

    private JsonWebKey getJwksFromCache(String keyId) {
        List<JsonWebKey> jwks = jwksCache;
        for (JsonWebKey jwk : jwks) {
            if (keyId.equals(jwk.getKeyId())) {
                return jwk;
            }
        }
        return null;
    }

    // observing startup event for bean to be created on app start so we catch errors early
    void onStartUp(@Observes StartupEvent event) {
    }
}
