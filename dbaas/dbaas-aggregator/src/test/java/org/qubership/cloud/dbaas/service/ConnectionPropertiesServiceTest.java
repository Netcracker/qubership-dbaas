package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.repositories.pg.jpa.DatabaseRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ConnectionPropertiesServiceTest {

    private final String RO_HOST = "roHost";
    @InjectMocks
    private ProcessConnectionPropertiesService connectionPropertiesService;

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    DatabaseRegistryRepository databaseRegistryRepository;

    @Test
    void addTlsFlagToConnectionPropertiesTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getFeatures()).thenReturn(Collections.singletonMap(TLS, true));

        connectionPropertiesService.addTlsFlagToConnectionProperties(db, physicalDatabase);
        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();
        connectionProperties.forEach(v -> {
            assertTrue(v.containsKey(TLS));
            assertTrue((Boolean) v.get(TLS));
        });
    }

    @Test
    void addTlsFlagWithTlsNotStrictToConnectionPropertiesTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        Map<String, Boolean> features = new HashMap<>();
        features.put(TLS, true);
        features.put(TLS_NOT_STRICT, true);
        when(physicalDatabase.getFeatures()).thenReturn(features);

        connectionPropertiesService.addTlsFlagToConnectionProperties(db, physicalDatabase);
        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();

        connectionProperties.forEach(v -> {
            assertTrue(v.containsKey(TLS));
            assertTrue((Boolean) v.get(TLS));
            assertTrue(v.containsKey(TLS_NOT_STRICT));
            assertTrue((Boolean) v.get(TLS_NOT_STRICT));
        });
    }

    @Test
    void isTlsNotStrictEnabledInAdapter() {
        Map<String, Boolean> features = new HashMap<>();
        features.put(TLS_NOT_STRICT, true);
        boolean tlsNotStrictEnabledInAdapter = connectionPropertiesService.isTlsNotStrictEnabledInAdapter(features);
        assertTrue(tlsNotStrictEnabledInAdapter);
        tlsNotStrictEnabledInAdapter = connectionPropertiesService.isTlsNotStrictEnabledInAdapter(Collections.emptyMap());
        assertFalse(tlsNotStrictEnabledInAdapter);

    }

    @Test
    void addRoHostToConnectionPropertiesTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getRoHost()).thenReturn("test-ro-host");

        connectionPropertiesService.addRoHostToConnectionProperties(db, physicalDatabase);
        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();
        connectionProperties.forEach(v -> {
            assertTrue(v.containsKey(RO_HOST));
            assertEquals("test-ro-host", v.get(RO_HOST));
        });
    }

    @Test
    void addTlsFlagToConnectionPropertiesWhenTlsIsDisabledTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getFeatures()).thenReturn(Collections.singletonMap(TLS, false));

        connectionPropertiesService.addTlsFlagToConnectionProperties(db, physicalDatabase);
        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();
        connectionProperties.forEach(v -> assertFalse(v.containsKey(TLS)));
        connectionProperties.forEach(v -> assertFalse(v.containsKey(TLS_NOT_STRICT)));
    }

    @Test
    void addRoHostToConnectionPropertiesWhenRoHostIsDisabledTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getRoHost()).thenReturn("");

        connectionPropertiesService.addRoHostToConnectionProperties(db, physicalDatabase);

        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();
        connectionProperties.forEach(v -> assertFalse(v.containsKey(RO_HOST)));
    }

    @Test
    void addTlsFlagToConnectionPropertiesWhenNoTLSTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getFeatures()).thenReturn(Collections.emptyMap());

        connectionPropertiesService.addTlsFlagToConnectionProperties(db, physicalDatabase);
        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();
        connectionProperties.forEach(v -> assertFalse(v.containsKey(TLS)));
    }

    @Test
    void addTlsFlagToConnectionPropertiesWhenNullTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        Database db = createDatabase(classifier, type, "adapter-id", "username", "dbname");
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getFeatures()).thenReturn(null);
        connectionPropertiesService.addTlsFlagToConnectionProperties(db, physicalDatabase);
        List<Map<String, Object>> connectionProperties = db.getConnectionProperties();
        connectionProperties.forEach(v -> assertFalse(v.containsKey(TLS)));
    }

    @Test
    void addTlsFlagToConnectionPropertiesForUser() {
        DatabaseUser user = Mockito.mock(DatabaseUser.class);
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        Map<String, Boolean> features = Map.of(TLS, true, TLS_NOT_STRICT, true);

        when(user.getConnectionProperties()).thenReturn(new HashMap<>());
        when(physicalDatabase.getFeatures()).thenReturn(features);

        connectionPropertiesService.addTlsFlagToConnectionProperties(user, physicalDatabase);

        Map<String, Object> connectionProperties = user.getConnectionProperties();
        assertTrue(connectionProperties.containsKey(TLS));
        assertTrue((Boolean) connectionProperties.get(TLS));
        assertTrue(connectionProperties.containsKey(TLS_NOT_STRICT));
        assertTrue((Boolean) connectionProperties.get(TLS_NOT_STRICT));
    }

    @Test
    void addRoHostToConnectionPropertiesForUserWhenRoHostIsEmpty() {
        DatabaseUser user = Mockito.mock(DatabaseUser.class);
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);

        when(user.getConnectionProperties()).thenReturn(new HashMap<>());
        when(physicalDatabase.getRoHost()).thenReturn("");

        connectionPropertiesService.addRoHostToConnectionProperties(user, physicalDatabase);

        Map<String, Object> connectionProperties = user.getConnectionProperties();
        assertFalse(connectionProperties.containsKey(RO_HOST));
    }

    private Database createDatabase(Map<String, Object> classifier, String type, String adapterId, String username, String dbName) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        database.setTimeDbCreation(new Date());
        database.setClassifier(new TreeMap<>(classifier));
        database.setType(type);
        database.setConnectionProperties(new ArrayList<>(List.of(new HashMap<String, Object>() {{
            put("username", username);
            put(ROLE, Role.ADMIN.toString());
        }})));
        database.setName(dbName);
        database.setAdapterId(adapterId);
        database.setSettings(new HashMap<String, Object>() {{
            put("setting-one", "value-one");
        }});
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setResources(new LinkedList<>(Arrays.asList(new DbResource("username", username),
                new DbResource("database", dbName))));
        return database;
    }
}
