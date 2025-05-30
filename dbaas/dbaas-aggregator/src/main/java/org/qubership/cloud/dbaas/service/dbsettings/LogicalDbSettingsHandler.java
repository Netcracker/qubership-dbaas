package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;

import java.util.Map;

public interface LogicalDbSettingsHandler {
    String updateSettings(DatabaseRegistry databaseRegistry, Map<String, Object> newSettings);

    String dbType();
}
