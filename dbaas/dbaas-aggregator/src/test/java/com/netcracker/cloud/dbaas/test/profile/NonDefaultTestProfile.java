package com.netcracker.cloud.dbaas.test.profile;

import com.netcracker.cloud.dbaas.integration.config.HybridM2MAuthModeProducer;
import com.netcracker.cloud.dbaas.integration.config.JwtUtilsTestResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// Test profile with all custom overrides for different tests.
// Using one merged test profile instead of different ones for performance reasons - less Quarkus restarts.
@NoArgsConstructor
public class NonDefaultTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> properties = new HashMap<>();
        properties.put("dbaas.process-orchestrator.enabled", "true");
        properties.put("quarkus.scheduler.enabled", "true");
        properties.put("quarkus.log.category.\"com.netcracker.cloud.dbaas.service.processengine.tasks\".level", "debug");
        properties.put("com.netcracker.cloud.security.kubernetes.service.account.token.dir", JwtUtilsTestResource.getTokenDir());
        return properties;
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(HybridM2MAuthModeProducer.class);
    }
}
