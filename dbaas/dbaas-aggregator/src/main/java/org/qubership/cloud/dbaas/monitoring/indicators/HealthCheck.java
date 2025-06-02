package org.qubership.cloud.dbaas.monitoring.indicators;

public interface HealthCheck {
    HealthCheckResponse check();
}
