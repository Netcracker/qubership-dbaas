package com.netcracker.cloud.dbaas.integration.utils;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class TestJwtUtils {
    private static final String issuer = "http://localhost:46567/auth/realms/quarkus";
    @Getter
    private static final int oidcPort = 46567;
    private final String audience;

    public TestJwtUtils(@ConfigProperty(name = "dbaas.security.k8s.jwt.audience") String audience) {
        this.audience = audience;
    }

    public String getJwt(String serviceAccountName, String namespace) {
        return Jwt.subject("some-service")
                .issuer(issuer)
                .audience(audience)
                .claim("jti", java.util.UUID.randomUUID().toString())
                .claim("kubernetes.io", Map.of(
                        "namespace", namespace,
                        "serviceaccount", Map.of("name", serviceAccountName)
                ))
                .expiresIn(Duration.ofDays(1))
                .issuedAt(Instant.now())
                .sign();
    }
}
