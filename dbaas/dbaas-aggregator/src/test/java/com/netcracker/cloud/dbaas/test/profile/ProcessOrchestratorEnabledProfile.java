package com.netcracker.cloud.dbaas.test.profile;

import io.quarkus.test.junit.QuarkusTestProfile;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@NoArgsConstructor
public class ProcessOrchestratorEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> properties = new HashMap<>();
        properties.put("dbaas.process-orchestrator.enabled", "true");
        properties.put("quarkus.log.category.\"com.netcracker.cloud.dbaas.service.processengine.tasks\".level", "debug");
        return properties;
    }
}
