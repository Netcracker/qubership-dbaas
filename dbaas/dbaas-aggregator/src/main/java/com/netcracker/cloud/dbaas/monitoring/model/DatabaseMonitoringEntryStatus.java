package com.netcracker.cloud.dbaas.monitoring.model;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

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
