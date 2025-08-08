package com.netcracker.cloud.dbaas.monitoring.indicators;

public interface HealthCheck {
    HealthCheckResponse check();
}
