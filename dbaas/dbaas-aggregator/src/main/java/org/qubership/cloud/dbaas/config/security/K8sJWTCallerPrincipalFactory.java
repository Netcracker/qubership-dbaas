package org.qubership.cloud.dbaas.config.security;

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
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.lang.JoseException;
import org.qubership.cloud.dbaas.rest.K8sOidcRestClient;

import java.security.Key;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
@Alternative
@Priority(1)
@Slf4j
public class K8sJWTCallerPrincipalFactory extends JWTCallerPrincipalFactory {
    private final Object lock = new Object();

    private final K8sOidcRestClient k8sOidcRestClient;

    private final String jwtJwksEndpoint;

    private final JwtConsumer jwtClaimsParser;

    private final RetryPolicy<Object> retryPolicy = new RetryPolicy<>()
            .withMaxRetries(5)
            .withBackoff(500, Duration.ofSeconds(60).toMillis(), ChronoUnit.MILLIS);

    private final AtomicReference<List<JsonWebKey>> jwksRef = new AtomicReference<>(new ArrayList<>());

    public K8sJWTCallerPrincipalFactory(@ConfigProperty(name = "dbaas.security.jwt.oidc-provider-url") String jwtIssuer,
                                        @ConfigProperty(name = "dbaas.security.jwt.audience") String jwtAudience,
                                        K8sOidcRestClient k8sOidcRestClient)
            throws Exception {
        this.k8sOidcRestClient = k8sOidcRestClient;

        jwtClaimsParser = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setAllowedClockSkewInSeconds(30)
                .setRequireSubject()
                .setExpectedIssuer(jwtIssuer)
                .setExpectedAudience(jwtAudience)
                .setSkipSignatureVerification()
                .build();

        jwtJwksEndpoint = k8sOidcRestClient.getOidcConfiguration(jwtIssuer).getJwks_uri();

        refreshOrFindJwks(null);
    }

    @Override
    public JWTCallerPrincipal parse(String token, JWTAuthContextInfo authContextInfo) throws ParseException {
        try {
            JwtContext jwtContext = jwtClaimsParser.process(token);
            JwtClaims claims = jwtContext.getJwtClaims();

            String keyId = jwtContext.getJoseObjects().getFirst().getKeyIdHeaderValue();
            JsonWebKey jsonWebKey = getJwk(keyId);

            if (jsonWebKey == null) {
                throw new ParseException("jwk not found");
            }

            if (!verifySignature(token, jsonWebKey.getKey())) {
                throw new ParseException("invalid jwt signature");
            }

            return new DefaultJWTCallerPrincipal(claims);
        } catch (InvalidJwtException | JoseException e) {
            throw new ParseException(e.getMessage());
        }
    }

    private boolean verifySignature(String token, Key key) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();

        jws.setCompactSerialization(token);
        jws.setKey(key);

        return jws.verifySignature();
    }

    private JsonWebKey refreshOrFindJwks(String keyId) {
        synchronized (lock) {
            if (keyId != null && !keyId.isEmpty()) {
                JsonWebKey jwk = findJwkFromJwks(keyId);
                if (jwk != null) {
                    return jwk;
                }
            }

            try {
                Failsafe.with(retryPolicy).run(() -> {
                    String rawJwks = (k8sOidcRestClient.getJwks(jwtJwksEndpoint));
                    jwksRef.set(new JsonWebKeySet(rawJwks).getJsonWebKeys());
                });
            } catch (TimeoutExceededException e) {
                log.error("Getting Json web keys from kubernetes jwks endpoint %s failed".formatted(jwtJwksEndpoint), e);
            }

            return null;
        }
    }

    private JsonWebKey getJwk(String keyId) throws JoseException {
        JsonWebKey jwk = findJwkFromJwks(keyId);
        if (jwk != null) {
            return jwk;
        }

        jwk = refreshOrFindJwks(keyId);
        if (jwk != null) {
            return jwk;
        }

        return findJwkFromJwks(keyId);
    }

    private JsonWebKey findJwkFromJwks(String keyId) {
        List<JsonWebKey> jwks = jwksRef.get();
        for (JsonWebKey jwk : jwks) {
            if (keyId.equals(jwk.getKeyId())) {
                return jwk;
            }
        }
        return null;
    }

    void onStartUp(@Observes StartupEvent event) {
    }
}
