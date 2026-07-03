package com.netcracker.cloud.dbaas.monitoring.indicators;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public interface HealthCheck {
    HealthCheckResponse check();
}
