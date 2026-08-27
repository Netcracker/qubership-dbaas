package com.netcracker.cloud.dbaas.integration.config;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class SchedulerEnabledTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.scheduler.enabled", "true");
    }
}
