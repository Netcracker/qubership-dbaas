package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.monitoring.indicators.*;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class HealthService {

    @Inject
    @All
    List<ProbeCheck> probeChecks;

    @Inject
    @All
    List<HealthCheck> healthChecks;

    public AggregatedHealthResponse getProbes() {
        return getAggregatedHealthResponse(probeChecks);
    }

    public AggregatedHealthResponse getHealth() {
        return getAggregatedHealthResponse(healthChecks);
    }

    @NotNull
    private AggregatedHealthResponse getAggregatedHealthResponse(List<? extends HealthCheck> healthChecks) {
        List<HealthCheckResponse> responses = healthChecks.stream().map(HealthCheck::check).toList();
        HealthStatus globalStatus = responses.stream().map(HealthCheckResponse::getStatus).max(Comparator.comparingInt(HealthStatus::getOrder)).orElse(HealthStatus.UP);
        return new AggregatedHealthResponse(
                globalStatus,
                responses.stream().collect(Collectors.toMap(HealthCheckResponse::getName, response -> response))
        );
    }
}
