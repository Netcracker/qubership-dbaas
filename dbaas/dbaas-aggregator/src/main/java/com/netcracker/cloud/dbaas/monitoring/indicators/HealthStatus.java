package com.netcracker.cloud.dbaas.monitoring.indicators;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum HealthStatus {
    UP(0),
    PROBLEM(1),
    DOWN(2);

    private final int order;
}
