package org.qubership.cloud.dbaas.monitoring.indicators;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@AllArgsConstructor
@Getter
public class AggregatedHealthResponse {
    private final HealthStatus status;
    private final Map<String, HealthCheckResponse> components;
}