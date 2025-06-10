package org.qubership.cloud.dbaas.service.dbsettings;

import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.exceptions.InvalidDbSettingsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.qubership.cloud.dbaas.service.dbsettings.PostgresqlSettingsHandler.DROP_EXTENSIONS;
import static org.qubership.cloud.dbaas.service.dbsettings.PostgresqlSettingsHandler.PG_EXTENSIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class PostgresqlSettingsHandlerTest {

    private PostgresqlSettingsHandler postgresqlSettingsHandler;
    private DefaultDbSettingsHandler defaultDbSettingsMock;
    private DatabaseRegistry databaseRegistry;


    private ArgumentCaptor<DatabaseRegistry> databaseRegistryCaptor;
    private ArgumentCaptor<Map<String, Object>> settingsCaptor;

    @BeforeEach
    public void setUp() {
        databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(new Database());
        defaultDbSettingsMock = mock(DefaultDbSettingsHandler.class);
        databaseRegistryCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        settingsCaptor = ArgumentCaptor.forClass(Map.class);
        postgresqlSettingsHandler = new PostgresqlSettingsHandler(defaultDbSettingsMock);
    }

    @Test
    public void testUpdateSettings_WithChangesInExtensions() {
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));
        databaseRegistry.setSettings(existingSettings);
        Map<String, Object> newSettings = new HashMap<>();
        newSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension3"));
        newSettings.put(DROP_EXTENSIONS, Collections.singletonList("extension2"));

        postgresqlSettingsHandler.updateSettings(databaseRegistry, newSettings);

        verify(defaultDbSettingsMock).updateSettings(databaseRegistryCaptor.capture(), settingsCaptor.capture());
        Map<String, Object> expectedSettings = Map.of(PG_EXTENSIONS, Arrays.asList("extension1", "extension3"));
        assertEquals(expectedSettings, settingsCaptor.getValue());
        assertEquals(existingSettings, databaseRegistry.getSettings());
        assertNull(newSettings.get(DROP_EXTENSIONS));
    }

    @Test
    public void testUpdateSettings_newSettingsContainsNewPgExtensions() {
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));
        databaseRegistry.setSettings(existingSettings);
        Map<String, Object> newSettings = new HashMap<>();
        newSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2", "extension3"));

        postgresqlSettingsHandler.updateSettings(databaseRegistry, newSettings);

        verify(defaultDbSettingsMock).updateSettings(databaseRegistryCaptor.capture(), settingsCaptor.capture());
        Map<String, Object> expectedSettings = Map.of(PG_EXTENSIONS, Arrays.asList("extension1", "extension2", "extension3"));
        assertEquals(expectedSettings, settingsCaptor.getValue());
        assertEquals(existingSettings, databaseRegistry.getSettings());
        assertNull(newSettings.get(DROP_EXTENSIONS));
    }

    @Test
    public void testUpdateSettings_newSettingsContainsLessPgExtensions() {
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));
        databaseRegistry.setSettings(existingSettings);
        Map<String, Object> newSettings = new HashMap<>();
        newSettings.put(PG_EXTENSIONS, List.of("extension1"));

        postgresqlSettingsHandler.updateSettings(databaseRegistry, newSettings);

        verify(defaultDbSettingsMock).updateSettings(databaseRegistryCaptor.capture(), settingsCaptor.capture());
        Map<String, Object> expectedSettings = Map.of(PG_EXTENSIONS, List.of("extension1", "extension2"));
        assertEquals(expectedSettings, settingsCaptor.getValue());
        assertEquals(existingSettings, databaseRegistry.getSettings());
        assertNull(newSettings.get(DROP_EXTENSIONS));
    }

    @Test
    public void testUpdateSettings_NoChangesInExtensions() {
        PostgresqlSettingsHandler postgresqlSettingsHandlerSpy = spy(new PostgresqlSettingsHandler(defaultDbSettingsMock));
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));
        databaseRegistry.setSettings(existingSettings);
        Map<String, Object> newSettings = new HashMap<>();
        newSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));

        postgresqlSettingsHandler.updateSettings(databaseRegistry, newSettings);

        verify(postgresqlSettingsHandlerSpy, times(0)).updatePgExtensions(anyMap(), anyMap());
        verify(defaultDbSettingsMock).updateSettings(databaseRegistryCaptor.capture(), settingsCaptor.capture());
        assertEquals(existingSettings, newSettings);
        assertEquals(existingSettings, settingsCaptor.getValue());
    }

    @Test
    public void testUpdateSettings_NullNewSettings() {
        PostgresqlSettingsHandler postgresqlSettingsHandlerSpy = spy(new PostgresqlSettingsHandler(defaultDbSettingsMock));
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));
        databaseRegistry.setSettings(existingSettings);

        postgresqlSettingsHandlerSpy.updateSettings(databaseRegistry, null);

        verify(postgresqlSettingsHandlerSpy, times(0)).updatePgExtensions(anyMap(), anyMap());
        verify(defaultDbSettingsMock).updateSettings(databaseRegistryCaptor.capture(), eq(null));
        assertEquals(existingSettings, databaseRegistryCaptor.getValue().getSettings());
    }

    @Test
    public void testUpdateSettings_EmptyNewSettings() {
        PostgresqlSettingsHandler postgresqlSettingsHandlerSpy = spy(new PostgresqlSettingsHandler(defaultDbSettingsMock));
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));
        databaseRegistry.setSettings(existingSettings);

        postgresqlSettingsHandlerSpy.updateSettings(databaseRegistry, new HashMap<>());

        verify(postgresqlSettingsHandlerSpy, times(0)).updatePgExtensions(anyMap(), anyMap());
        verify(defaultDbSettingsMock).updateSettings(databaseRegistryCaptor.capture(), eq(new HashMap<>()));
        assertEquals(existingSettings, databaseRegistryCaptor.getValue().getSettings());
    }

    @Test
    public void testUpdateSettings_ErrorInDefaultDbSettings() {
        Map<String, Object> existingSettings = new HashMap<>();
        existingSettings.put(PG_EXTENSIONS, Arrays.asList("extension1", "extension2"));

        databaseRegistry.setSettings(existingSettings);

        Map<String, Object> newSettings = new HashMap<>();
        newSettings.put(PG_EXTENSIONS, List.of("extension1", "extension2"));
        newSettings.put(DROP_EXTENSIONS, List.of("extension2"));

        InvalidDbSettingsException invalidDbSettingsException = assertThrows(InvalidDbSettingsException.class,
                () -> postgresqlSettingsHandler.updateSettings(databaseRegistry, newSettings));
        assertTrue(invalidDbSettingsException.getMessage().contains("'pgExtensions' and 'dropExtensions' parameters contain " +
                "common extensions. You must specify whether it is needed to remove or add these extensions. " +
                "Settings in request: {dropExtensions=[extension2], pgExtensions=[extension1, extension2]}"));
    }

}