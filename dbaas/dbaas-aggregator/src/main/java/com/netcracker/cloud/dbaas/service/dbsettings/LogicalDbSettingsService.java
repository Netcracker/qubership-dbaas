package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.service.dbsettings.DefaultDbSettingsHandler.DEFAULT_DB_SETTING_HANDLER_TYPE;

@Slf4j
public class LogicalDbSettingsService {

    private Map<String, LogicalDbSettingsHandler> logicalDbSettingsHandlers;

    public LogicalDbSettingsService(List<LogicalDbSettingsHandler> logicalDbSettingsHandlers) {
        this.logicalDbSettingsHandlers = logicalDbSettingsHandlers.stream()
                .collect(Collectors.toMap(LogicalDbSettingsHandler::dbType, Function.identity()));
    }

    @Transactional
    public String updateSettings(DatabaseRegistry databaseRegistry, Map<String, Object> newSettings) {
        LogicalDbSettingsHandler logicalDbSettingsHandler = logicalDbSettingsHandlers
                .computeIfAbsent(databaseRegistry.getType(), key -> logicalDbSettingsHandlers.get(DEFAULT_DB_SETTING_HANDLER_TYPE));

        return logicalDbSettingsHandler.updateSettings(databaseRegistry, newSettings);
    }
}
