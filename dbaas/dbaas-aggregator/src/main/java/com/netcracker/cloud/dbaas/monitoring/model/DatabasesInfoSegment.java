package com.netcracker.cloud.dbaas.monitoring.model;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collection;

@Data
@AllArgsConstructor
public class DatabasesInfoSegment {
    private String name;
    private Collection<DatabaseInfo> totalDatabases;
    private DatabasesRegistrationInfo registration;
    private Collection<DatabaseInfo> deletingDatabases;
}
