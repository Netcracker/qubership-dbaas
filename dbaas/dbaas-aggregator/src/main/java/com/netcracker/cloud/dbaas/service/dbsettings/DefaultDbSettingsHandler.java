package com.netcracker.cloud.dbaas.service.dbsettings;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import com.netcracker.cloud.dbaas.service.DbaasAdapter;
import com.netcracker.cloud.dbaas.service.PhysicalDatabasesService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;

@Slf4j
public class DefaultDbSettingsHandler implements LogicalDbSettingsHandler {

    static final String DEFAULT_DB_SETTING_HANDLER_TYPE = "default";
    private PhysicalDatabasesService physicalDatabasesService;
    private LogicalDbDbaasRepository logicalDbDbaasRepository;

    public DefaultDbSettingsHandler(PhysicalDatabasesService physicalDatabasesService,
                                    LogicalDbDbaasRepository logicalDbDbaasRepository) {
        this.physicalDatabasesService = physicalDatabasesService;
        this.logicalDbDbaasRepository = logicalDbDbaasRepository;
    }

    @Override
    public String dbType() {
        return DEFAULT_DB_SETTING_HANDLER_TYPE;
    }

    @Override
    @Transactional
    public String updateSettings(DatabaseRegistry databaseRegistry, Map<String, Object> newSettings) {
        Objects.requireNonNull(databaseRegistry, "database cannot be null");
        if (databaseRegistry.isExternallyManageable()) {
            return "";
        }
        if (newSettings == null || newSettings.isEmpty()) {
            log.info("New settings are empty");
            return "";
        }

        Map<String, Object> currentSettings = databaseRegistry.getSettings();
        if (newSettings.equals(currentSettings)) {
            log.info("Nothing to update, settings are equal.");
            return "";
        }

        DbaasAdapter dbaasAdapter = physicalDatabasesService.getAdapterById(databaseRegistry.getAdapterId());
        if (dbaasAdapter == null) {
            throw new UnregisteredPhysicalDatabaseException("Adapter identifier: " + databaseRegistry.getAdapterId());
        }


        String message = dbaasAdapter.updateSettings(databaseRegistry.getName(), currentSettings, newSettings);
StructuredLog.debug(log, "Message from adapter:", "message", message);
        // update settings in dbaas db
        databaseRegistry.setSettings(newSettings);
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveInternalDatabase(databaseRegistry);
StructuredLog.info(log, "Successfully updated settings for db, from original settings: to new settings:. Message from adapter:", "database", databaseRegistry.getName(), "currentSettings", currentSettings, "newSettings", newSettings, "message", message);
        return message;
    }
}
