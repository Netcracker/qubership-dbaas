package com.netcracker.cloud.dbaas.monitoring.model;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collection;

@Data
@AllArgsConstructor
public class DatabasesRegistrationInfo {
    private Collection<DatabaseInfo> totalDatabases;
    private Collection<DatabaseInfo> lostDatabases;
    private Collection<DatabaseInfo> ghostDatabases;
}
