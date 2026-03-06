package com.netcracker.cloud.dbaas.integration.config;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class SecurityTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "dbaas.security.k8s.jwt.enabled", "true",
                "com.netcracker.cloud.security.kubernetes.service.account.token.dir", JwtUtilsTestResource.tokenDir
        );
    }
}
