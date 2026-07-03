package com.netcracker.cloud.dbaas.monitoring.indicators;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@AllArgsConstructor
@Getter
public class AggregatedHealthResponse {
    private final HealthStatus status;
    private final Map<String, HealthCheckResponse> components;
}
