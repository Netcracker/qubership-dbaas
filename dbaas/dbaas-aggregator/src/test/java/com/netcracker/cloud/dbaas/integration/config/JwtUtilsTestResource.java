package com.netcracker.cloud.dbaas.integration.config;

import com.netcracker.cloud.dbaas.integration.utils.TestJwtUtils;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.eclipse.microprofile.config.ConfigProvider;

import java.time.Duration;
import java.util.Map;

public class JwtUtilsTestResource implements QuarkusTestResourceLifecycleManager {
    public static TestJwtUtils JWT_UTILS;
    private final OidcWiremockTestResource oidcWiremock = new OidcWiremockTestResource();

    @Override
    public Map<String, String> start() {
        var conf = oidcWiremock.start();
        String issuer = conf.get("keycloak.url")+"/realms/quarkus";
        String audience = ConfigProvider.getConfig()
                .getValue("dbaas.security.k8s.jwt.audience", String.class);
        String tokenDir = ConfigProvider.getConfig()
                .getValue(
                        "com.netcracker.cloud.security.kubernetes.service.account.token.dir",
                        String.class);
        System.setProperty("smallrye.jwt.sign.key.location", conf.get("smallrye.jwt.sign.key.location"));
        JWT_UTILS = new TestJwtUtils(issuer, audience, tokenDir);
        return conf;
    }

    @Override
    public void stop() {
        JWT_UTILS = null;
    }
}
