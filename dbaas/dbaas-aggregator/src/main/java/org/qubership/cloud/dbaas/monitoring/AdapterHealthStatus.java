package org.qubership.cloud.dbaas.monitoring;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdapterHealthStatus {
    public final static String HEALTH_CHECK_STATUS_PROBLEM = "PROBLEM";
    public final static String HEALTH_CHECK_STATUS_UNKNOWN = "UNKNOWN";
    public final static String HEALTH_CHECK_STATUS_UP = "UP";
    String status;
}