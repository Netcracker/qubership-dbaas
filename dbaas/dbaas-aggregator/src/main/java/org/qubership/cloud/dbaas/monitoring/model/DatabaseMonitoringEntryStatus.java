package org.qubership.cloud.dbaas.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DatabaseMonitoringEntryStatus {
    private String namespace;
    private String microservice;
    private String databaseType;
    private String databaseName;
    private String status;
    private String host;
    private String externallyManageable;
}
