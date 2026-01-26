package com.netcracker.cloud.dbaas.integration.utils;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.SneakyThrows;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class TestJwtUtils {
    private final String issuer;
    private final String audience;

    @SneakyThrows
    public TestJwtUtils(String issuer, String audience, String tokenDir) {
        this.issuer = issuer;
        this.audience = audience;

        Files.createDirectories(Path.of(tokenDir));
        Files.writeString(
                Path.of(tokenDir).resolve("token"),
                getJwt("default", "test-namespace"),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
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
