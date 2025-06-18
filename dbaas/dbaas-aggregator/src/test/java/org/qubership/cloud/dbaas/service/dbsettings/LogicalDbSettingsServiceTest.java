package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class LogicalDbSettingsServiceTest {

    private LogicalDbSettingsService logicalDbSettingsService;
    private DefaultDbSettingsHandler defaultDbSettingsHandler;
    private PostgresqlSettingsHandler postgresqlSettingsHandler;

    @BeforeEach
    void setUp() {
        defaultDbSettingsHandler = Mockito.mock(DefaultDbSettingsHandler.class);
        postgresqlSettingsHandler = Mockito.mock(PostgresqlSettingsHandler.class);
        when(defaultDbSettingsHandler.dbType()).thenReturn(DefaultDbSettingsHandler.DEFAULT_DB_SETTING_HANDLER_TYPE);
        when(postgresqlSettingsHandler.dbType()).thenReturn("postgresql");
        logicalDbSettingsService = new LogicalDbSettingsService(List.of(defaultDbSettingsHandler, postgresqlSettingsHandler));
    }

    @Test
    void updateSettings_PostgresqlSettingsHandler() {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType("postgresql");

        Map<String, Object> newSettings = Collections.emptyMap();
        logicalDbSettingsService.updateSettings(databaseRegistry, newSettings);

        verify(postgresqlSettingsHandler, times(1)).updateSettings(databaseRegistry, newSettings);
        verify(defaultDbSettingsHandler, times(0)).updateSettings(databaseRegistry, newSettings);
    }

    @Test
    void updateSettings_DefaultSettingsHandler() {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setType("mongodb");

        Map<String, Object> newSettings = Collections.emptyMap();
        logicalDbSettingsService.updateSettings(databaseRegistry, newSettings);

        verify(postgresqlSettingsHandler, times(0)).updateSettings(databaseRegistry, newSettings);
        verify(defaultDbSettingsHandler, times(1)).updateSettings(databaseRegistry, newSettings);
    }
}