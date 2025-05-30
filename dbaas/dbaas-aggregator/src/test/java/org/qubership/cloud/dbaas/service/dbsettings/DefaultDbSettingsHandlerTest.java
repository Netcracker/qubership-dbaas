package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qubership.cloud.dbaas.service.dbsettings.DefaultDbSettingsHandler.DEFAULT_DB_SETTING_HANDLER_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDbSettingsHandlerTest {
    private DefaultDbSettingsHandler defaultDbSettingsHandler;
    private PhysicalDatabasesService physicalDatabasesService;
    private LogicalDbDbaasRepository logicalDbDbaasRepository;
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @BeforeEach
    public void setUp() {
        physicalDatabasesService = Mockito.mock(PhysicalDatabasesService.class);
        logicalDbDbaasRepository = Mockito.mock(LogicalDbDbaasRepository.class);
        databaseRegistryDbaasRepository = Mockito.mock(DatabaseRegistryDbaasRepository.class);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        defaultDbSettingsHandler = new DefaultDbSettingsHandler(physicalDatabasesService, logicalDbDbaasRepository);
    }

    @Test
    void dbTypeTest() {
        assertEquals(DEFAULT_DB_SETTING_HANDLER_TYPE, defaultDbSettingsHandler.dbType());
        assertEquals(DEFAULT_DB_SETTING_HANDLER_TYPE, "default");
    }

    @Test
    void updateSettingsForExternalDatabaseTest_settingsAreNotChanged() {
        String settingName = "setting_1";
        Map<String, Object> originalSettings = new HashMap<>() {{
            put(settingName, true);
        }};
        Map<String, Object> settings = new HashMap<>() {{
            put(settingName, false);
        }};

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setExternallyManageable(true);
        databaseRegistry.setSettings(originalSettings);
        database.setDatabaseRegistry(List.of(new DatabaseRegistry()));

        defaultDbSettingsHandler.updateSettings(databaseRegistry, settings);

        Assertions.assertTrue((Boolean) databaseRegistry.getSettings().get(settingName));
    }

    @Test
    void testSettingUpdateSuccess() {
        Map<String, Object> originalSettings = new HashMap<>() {{
            put("original-setting-#1", Stream.of("original-item1", "original-item2").collect(Collectors.toList()));
        }};
        Map<String, Object> settings = new HashMap<>() {{
            put("setting-#1", Stream.of("item1", "item2").collect(Collectors.toList()));
            put("setting-#2", true);
        }};

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);
        String adapterId = "test-adapter-id";
        String dbName = "test-db-name";
        databaseRegistry.setAdapterId(adapterId);
        databaseRegistry.setName(dbName);
        databaseRegistry.setSettings(originalSettings);
        database.setDatabaseRegistry(List.of(new DatabaseRegistry()));
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(adapter.identifier()).thenReturn(adapterId);
        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(adapter);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);

        defaultDbSettingsHandler.updateSettings(databaseRegistry, settings);

        verify(adapter).updateSettings(Mockito.eq(dbName), Mockito.eq(originalSettings), Mockito.eq(settings));
        ArgumentCaptor<DatabaseRegistry> databaseArgumentCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        verify(databaseRegistryDbaasRepository).saveInternalDatabase(databaseArgumentCaptor.capture());
        Assertions.assertEquals(databaseArgumentCaptor.getValue().getSettings(), settings);
    }

    @Test
    void testSettingUpdateError() {
        Map<String, Object> originalSettings = new HashMap<>() {{
            put("original-setting-#1", Stream.of("original-item1", "original-item2").collect(Collectors.toList()));
        }};
        Map<String, Object> settings = new HashMap<>() {{
            put("setting-#1", Stream.of("item1", "item2").collect(Collectors.toList()));
            put("setting-#2", true);
        }};

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);
        String adapterId = "test-adapter-id";
        String dbName = "test-db-name";
        databaseRegistry.setAdapterId(adapterId);
        databaseRegistry.setName(dbName);
        databaseRegistry.setSettings(originalSettings);
        database.setDatabaseRegistry(List.of(new DatabaseRegistry()));
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(adapter.identifier()).thenReturn(adapterId);
        when(physicalDatabasesService.getAdapterById(adapterId)).thenReturn(adapter);

        WebApplicationException exception = new InternalServerErrorException("test-message");

        doThrow(exception).when(adapter).updateSettings(Mockito.eq(dbName), Mockito.any(), Mockito.any());
        Assertions.assertThrows(WebApplicationException.class, () -> defaultDbSettingsHandler.updateSettings(databaseRegistry, settings));
        verify(databaseRegistryDbaasRepository, Mockito.never()).saveAnyTypeLogDb(Mockito.any(DatabaseRegistry.class));
    }
}