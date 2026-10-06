package com.netcracker.cloud.dbaas.integration.config;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;
import java.util.Set;

public class SecurityTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "com.netcracker.cloud.security.kubernetes.service.account.token.dir", JwtUtilsTestResource.getTokenDir()
        );
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(HybridM2MAuthModeProducer.class);
    }
}
