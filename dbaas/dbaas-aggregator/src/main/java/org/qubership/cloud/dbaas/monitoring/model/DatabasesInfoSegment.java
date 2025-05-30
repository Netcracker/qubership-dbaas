package org.qubership.cloud.dbaas.monitoring.model;

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
