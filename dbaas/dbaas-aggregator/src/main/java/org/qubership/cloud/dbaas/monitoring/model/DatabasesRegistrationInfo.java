package org.qubership.cloud.dbaas.monitoring.model;

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