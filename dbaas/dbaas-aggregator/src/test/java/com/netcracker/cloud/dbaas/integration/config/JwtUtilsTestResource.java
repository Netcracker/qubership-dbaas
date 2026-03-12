package com.netcracker.cloud.dbaas.integration.config;

import com.netcracker.cloud.dbaas.integration.utils.TestJwtUtils;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import lombok.SneakyThrows;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Files;
import java.util.Map;

public class JwtUtilsTestResource implements QuarkusTestResourceLifecycleManager {
    private static String tokenDir;
    public static TestJwtUtils JWT_UTILS;
    private final OidcWiremockTestResource oidcWiremock = new OidcWiremockTestResource();

    @Override
    @SneakyThrows
    public Map<String, String> start() {
        var conf = oidcWiremock.start();
        String issuer = conf.get("keycloak.url")+"/realms/quarkus";
        String audience = ConfigProvider.getConfig()
                .getValue("dbaas.security.k8s.jwt.audience", String.class);
        System.setProperty("smallrye.jwt.sign.key.location", conf.get("smallrye.jwt.sign.key.location"));
        JWT_UTILS = new TestJwtUtils(issuer, audience, getTokenDir());
        return conf;
    }

    @Override
    public void stop() {
        JWT_UTILS = null;
    }

    @SneakyThrows
    public static String getTokenDir() {
        if(tokenDir == null) {
            tokenDir = Files.createTempDirectory("mock-oidc-token").toAbsolutePath().toString();
        }
        return tokenDir;
    }
}
