package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
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
        log.debug("Message from adapter: {}", message);
        // update settings in dbaas db
        databaseRegistry.setSettings(newSettings);
        logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository().saveInternalDatabase(databaseRegistry);
        log.info("Successfully updated settings for db {}, from original settings: {} to new settings: {}. Message from adapter: {}", databaseRegistry.getName(), currentSettings, newSettings, message);
        return message;
    }
}
