package com.netcracker.cloud.dbaas.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.Constants;
import com.netcracker.cloud.dbaas.dto.DatabaseResponse;
import com.netcracker.cloud.dbaas.dto.FailedTransformationDatabaseResponse;
import com.netcracker.cloud.dbaas.dto.PasswordChangeResponse;
import com.netcracker.cloud.dbaas.dto.UpdateConnectionPropertiesRequest;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.*;
import com.netcracker.cloud.dbaas.entity.pg.*;
import com.netcracker.cloud.dbaas.exceptions.InvalidUpdateConnectionPropertiesRequestException;
import com.netcracker.cloud.dbaas.exceptions.NoBalancingRuleException;
import com.netcracker.cloud.dbaas.exceptions.NotFoundException;
import com.netcracker.cloud.dbaas.exceptions.UnregisteredPhysicalDatabaseException;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseHistoryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.LogicalDbDbaasRepository;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.MatcherAssert;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.util.StringUtils;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.netcracker.cloud.dbaas.Constants.MICROSERVICE_NAME;
import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.service.PasswordEncryption.PASSWORD_FIELD;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class DBaaServiceTest {

    @InjectMocks
    private DBaaService dBaaService;

    @Mock
    private PasswordEncryption encryption;

    @Mock
    private PhysicalDatabasesService physicalDatabasesService;

    @Mock
    private LogicalDbDbaasRepository logicalDbDbaasRepository;

    @Mock
    private DatabaseDbaasRepository databaseDbaasRepository;

    @Mock
    private DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;

    @Mock
    private DatabaseHistoryDbaasRepository databaseHistoryDbaasRepository;

    @Mock
    private BalancingRulesService balancingRulesService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ProcessConnectionPropertiesService connectionPropertiesService;


    private static final String NAMESPACE = "test-namespace";
    private static final String PG_TYPE = "postgresql";

    @Test
    void testGetMergedConnectionProperties_passwordChanges() {
        Map<String, Object> prevConnProperties = new HashMap<>();
        prevConnProperties.put("username", "my-user");
        prevConnProperties.put("password", "my-password");

        Map<String, Object> currConnProperties = new HashMap<>();
        currConnProperties.put("username", "my-user");
        currConnProperties.put("password", "my-new-password");

        Map<String, Object> result = dBaaService.getMergedConnectionProperties(prevConnProperties, currConnProperties);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(currConnProperties, result);
    }

    @Test
    void testGetMergedConnectionProperties_adapterDoNotReturnsResourcePrefixAndReturnedNewMapping() {
        Map<String, Object> prevConnProperties = new HashMap<>();
        prevConnProperties.put("username", "my-user");
        prevConnProperties.put("password", "my-password");
        prevConnProperties.put("resourcePrefix", "my-resource-prefix");

        Map<String, Object> currConnProperties = new HashMap<>();
        currConnProperties.put("username", "my-user");
        currConnProperties.put("password", "my-password");
        currConnProperties.put("some-new-conn-property-key", "some-new-val");

        Map<String, Object> result = dBaaService.getMergedConnectionProperties(prevConnProperties, currConnProperties);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(4, result.size());
        Assertions.assertEquals("my-user", result.get("username"));
        Assertions.assertEquals("my-password", result.get("password"));
        Assertions.assertEquals("my-resource-prefix", result.get("resourcePrefix"));
        Assertions.assertEquals("some-new-val", result.get("some-new-conn-property-key"));
    }

    @Test
    void serialize() throws IOException {
        PasswordChangeResponse passwordChangeResponse = new PasswordChangeResponse();
        passwordChangeResponse.putSuccessEntity(Collections.singletonMap("classifier", "value"), Collections.singletonMap("url", "value"));
        passwordChangeResponse.putFailedEntity(Collections.singletonMap("classifier", "value"), "err");
        String serialize = new ObjectMapper().writeValueAsString(passwordChangeResponse);
        Assertions.assertTrue(StringUtils.isNotBlank(serialize));
    }

    @Test
    void checkModifiedFieldsForExternalDatabase_resultAlwaysIsFalse() {
        DatabaseCreateRequestV3 createRequest = new DatabaseCreateRequestV3();
        createRequest.setBackupDisabled(true);

        Database database = new Database();
        database.setExternallyManageable(true);
        database.setBackupDisabled(false);

        Boolean isModifiedFields = dBaaService.isModifiedFields(createRequest, database);

        Assertions.assertFalse(isModifiedFields);
    }

    @Test
    void saveExternalDatabaseTest() {

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.saveExternalDatabase(any())).thenAnswer(i -> i.getArguments()[0]);
        ExternalDatabaseRequestV3 externalDatabaseRequest = getExternalDatabaseRequestObject();
        DatabaseRegistry database = externalDatabaseRequest.toDatabaseRegistry();
        DatabaseRegistry createdDatabase = dBaaService.saveExternalDatabase(database);
        log.info("Database {}", createdDatabase);
        Assertions.assertEquals("external-db-user", ConnectionPropertiesUtils.getConnectionProperties(createdDatabase.getConnectionProperties(), Role.ADMIN.toString()).get("username"));
        Assertions.assertEquals("test-namespace", createdDatabase.getClassifier().get("namespace"));
        Assertions.assertTrue(createdDatabase.isExternallyManageable());
    }

    @Test
    void testFindDocumentByClassifierAndType() {
        final UUID databaseRegistryId = UUID.randomUUID();
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(anyMap(), anyString())).thenAnswer(invocation -> {
            Database database = new Database();
            database.setName("search-result-db");
            DatabaseRegistry databaseRegistry = new DatabaseRegistry();
            databaseRegistry.setDatabase(database);
            databaseRegistry.setId(databaseRegistryId);
            database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
            return Optional.of(databaseRegistry);
        });
        Map<String, Object> classifier = new HashMap<>(0);
        DatabaseRegistry searchResult = dBaaService.findDatabaseByClassifierAndType(classifier, "mongodb", false);
        assertNull(searchResult.getId());

        searchResult = dBaaService.findDatabaseByClassifierAndType(classifier, "mongodb", true);
        assertEquals(databaseRegistryId, searchResult.getId());
    }

    @Test
    void updateClassifierWithoutOriginalZnodeTest() {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("isServiceDb", true);
        }};
        String mongodb = "mongodb";
        DatabaseRegistry database = createDatabase(classifier, mongodb, null, "zk-user-name", "zk-db-name");
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("tenantId", 1234);
        }};
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(classifier), eq(mongodb))).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));
        when(databaseHistoryDbaasRepository.getLastVersionByName(database.getName())).thenReturn(0);
        DatabaseRegistry actualDatabase = dBaaService.updateClassifier(classifier, targetClassifier, mongodb, false);
        Assertions.assertEquals(targetClassifier, actualDatabase.getClassifier());
    }

    @Test
    void updateClassifierWithCloneTest() {

        SortedMap<String, Object> classifier = new TreeMap<>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("scope", "service");
        }};
        String mongodb = "postgresql";
        DatabaseRegistry database = createDatabase(classifier, mongodb, null, "zk-user-name", "zk-db-name");

        SortedMap<String, Object> oldClassifier = new TreeMap<>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("isServiceDb", true);
        }};
        database.setOldClassifier(oldClassifier);

        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(classifier), eq(mongodb))).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        SortedMap<String, Object> targetClassifier = new TreeMap<>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("tenantId", 1234);
            put("scope", "tenant");
        }};

        UUID sourceId = database.getId();

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        AtomicBoolean copyIsSaved = new AtomicBoolean(false);
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any(DatabaseRegistry.class))).thenAnswer(invocation -> {
            DatabaseRegistry db = invocation.getArgument(0);
            if (!copyIsSaved.get()) {
                Assertions.assertEquals(sourceId, db.getId());
                Assertions.assertNotNull(db.getOldClassifier());
                Assertions.assertTrue(db.isExternallyManageable());
                copyIsSaved.set(true);
            } else {
                Assertions.assertNotEquals(sourceId, db.getId());
                Assertions.assertNull(db.getOldClassifier());
                Assertions.assertFalse(db.isExternallyManageable());
            }
            return db;
        });

        DatabaseRegistry actualDatabase = dBaaService.updateClassifier(classifier, targetClassifier, mongodb, true);

        Assertions.assertEquals(targetClassifier, actualDatabase.getClassifier());
        Assertions.assertNotEquals(sourceId, actualDatabase.getId());
        //TODO Sonya what is done here? Should we return after if?
//        Mockito.verify(databaseDbaasRepository).saveAnyTypeLogDb(database);
//        Mockito.verify(databaseDbaasRepository).saveAnyTypeLogDb(actualDatabase);
    }

    @Test
    void updateClassifierAndNoZkReplicateTest() {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("isServiceDb", true);
        }};
        String mongodb = "mongodb";
        DatabaseRegistry database = createDatabase(classifier, mongodb, null, "zk-user-name", "zk-db-name");
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
            put("namespace", "test-namespace");
            put("microserviceName", "serviceOne");
            put("tenantId", 1234);
        }};
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(eq(classifier), eq(mongodb))).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));
        when(databaseHistoryDbaasRepository.getLastVersionByName(database.getName())).thenReturn(0);
        DatabaseRegistry actualDatabase = dBaaService.updateClassifier(classifier, targetClassifier, mongodb, false);
        Assertions.assertEquals(targetClassifier, actualDatabase.getClassifier());
    }

    private ExternalDatabaseRequestV3 getExternalDatabaseRequestObject() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test");
        classifier.put("scope", "service");
        classifier.put("namespace", "test-namespace");
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("url", "http://test.internal");
        connectionProperties.put("port", 0);
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        connectionProperties.put("username", "external-db-user");
        connectionProperties.put("password", "password");
        return new ExternalDatabaseRequestV3(classifier,
                Collections.singletonList(connectionProperties), "tarantool", "external-db");
    }

    private Map<String, Object> createSettings(Map<String, Object> indexSettings) {
        Map<String, Object> map = new HashMap<>();
        map.put("indexSettings", indexSettings);
        map.put("key2", "value2");
        return map;
    }

    private DatabaseRegistry createDatabase(Map<String, Object> classifier, String type, String adapterId, String username, String dbName) {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        ArrayList<Map<String, Object>> connectionProperties = new ArrayList<>(List.of(new HashMap<String, Object>() {{
            put("username", username);
            put(ROLE, Role.ADMIN.toString());
        }}));
        database.setConnectionProperties(connectionProperties);
        database.setClassifier(new TreeMap<>(classifier));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setTimeDbCreation(new Date());
        databaseRegistry.setNamespace(NAMESPACE);
        databaseRegistry.setClassifier(new TreeMap<>(classifier));
        databaseRegistry.setType(type);
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        databaseRegistry.setDatabase(database);
        database.setName(dbName);
        database.setAdapterId(adapterId);
        database.setSettings(new HashMap<String, Object>() {{
            put("setting-one", "value-one");
        }});
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        database.setResources(new LinkedList<>(Arrays.asList(new DbResource("username", username),
                new DbResource("database", dbName))));
        return databaseRegistry;
    }

    private DatabaseRegistry createDatabase(Map<String, Object> classifier, String type, String adapterId, String username, String dbName, SortedMap<String, Object> oldClassifier) {
        DatabaseRegistry database = createDatabase(classifier, type, adapterId, username, dbName);
        database.setOldClassifier(oldClassifier);
        return database;
    }

    @Test
    void recreateDatabase() {
        String adapterId = "old adapter", username = "old username", dbName = "old dbName", physId = "123";
        String newAdapterId = "new adapter", newUsername = "new username", newDbName = "new dbName", password = "new pwd";
        Map<String, Object> classifier = testClassifier();
        DatabaseRegistry exitedDb = createDatabase(classifier, PG_TYPE, adapterId, username, dbName);
        exitedDb.setNamespace(NAMESPACE);
        exitedDb.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        DbaasAdapter dbaasAdapter = mock(DbaasAdapter.class);
        Mockito.when(dbaasAdapter.identifier()).thenReturn(newAdapterId);
        PhysicalDatabase physicalDatabaseRegistration = Mockito.mock(PhysicalDatabase.class);
        Mockito.when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(eq(physId))).thenReturn(physicalDatabaseRegistration);
        ExternalAdapterRegistrationEntry adapterRegistrationEntry = Mockito.mock(ExternalAdapterRegistrationEntry.class);
        Mockito.when(physicalDatabaseRegistration.getAdapter()).thenReturn(adapterRegistrationEntry);
        Mockito.when(adapterRegistrationEntry.getAdapterId()).thenReturn(newAdapterId);
        Mockito.when(physicalDatabasesService.getAdapterById(eq(newAdapterId))).thenReturn(dbaasAdapter);

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        CreatedDatabaseV3 createdDatabase = getCreatedDatabase(newUsername, newAdapterId, newDbName, password);
        Mockito.when(dbaasAdapter.createDatabaseV3(any(DatabaseCreateRequestV3.class), eq("test-microservice"))).thenReturn(createdDatabase);
        Mockito.when(databaseRegistryDbaasRepository.saveExternalDatabase(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseRegistry newDb = dBaaService.recreateDatabase(exitedDb, physId);

        verify(dbaasAdapter).createDatabaseV3(argThat(databaseCreateRequest ->
                classifier.equals(databaseCreateRequest.getClassifier())
                        && exitedDb.getSettings().equals(databaseCreateRequest.getSettings())
        ), eq("test-microservice"));
        verify(physicalDatabasesService).getByPhysicalDatabaseIdentifier(physId);
        ArgumentCaptor<DatabaseRegistry> dbCaptor = ArgumentCaptor.forClass(DatabaseRegistry.class);
        verify(databaseRegistryDbaasRepository, times(2)).saveExternalDatabase(dbCaptor.capture());
        SortedMap<String, Object> archivedClassifier = dbCaptor.getAllValues().get(0).getClassifier();
        SortedMap<String, Object> newClassifier = dbCaptor.getAllValues().get(1).getClassifier();
        Assertions.assertNotNull(archivedClassifier.get("archived"));
        Assertions.assertNull(newClassifier.get("archived"));
        MatcherAssert.assertThat(exitedDb, checkNewlyCreated(newDb));
    }

    @Test
    void testDbStateEqualsNotNull() {
        Database db = new Database();
        db.setDbState(null);
        Assertions.assertNotNull(db.getDbState());
    }

    @Test
    void testSuccessfullyUpdateConnectionProperties() throws JsonProcessingException {
        String dbType = "mongodb";
        String databaseName = "database-name";
        String userName = "user-name";
        String newUserName = "test-username";
        String adapterId = "test-adapter-id";
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", NAMESPACE);
            put("microserviceName", "serviceOne");
        }};
        DatabaseRegistry database = createDatabase(classifier, dbType, adapterId, userName, databaseName);
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newUserName);
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(adapter.identifier()).thenReturn(adapterId);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Collections.singletonList(adapter));
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, dbType)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        DatabaseRegistry updatedDatabase = dBaaService.updateDatabaseConnectionProperties(request, dbType);
        assertNotNull(updatedDatabase);


        Map<String, Object> actualConnection = ConnectionPropertiesUtils.getConnectionProperties(updatedDatabase.getConnectionProperties(), Role.ADMIN).get();
        assertEquals(request.getConnectionProperties(), actualConnection);

        verify(encryption, times(1)).deletePassword(database.getDatabase(), Role.ADMIN.toString());
        verify(encryption, times(1)).encryptPassword(database.getDatabase(), Role.ADMIN.toString());
        verify(databaseRegistryDbaasRepository, times(1)).saveAnyTypeLogDb(database);
    }

    @Test
    void testSuccessfullyUpdateConnectionPropertiesAndAdapterNotFound() throws JsonProcessingException {
        String dbType = "mongodb";
        String databaseName = "database-name";
        String userName = "user-name";
        String newUserName = "test-username";
        String adapterId = "test-adapter-id";
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", NAMESPACE);
            put("microserviceName", "serviceOne");
        }};
        DatabaseRegistry database = createDatabase(classifier, dbType, adapterId, userName, databaseName);
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newUserName);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Collections.emptyList());
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, dbType)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        DatabaseRegistry updatedDatabase = dBaaService.updateDatabaseConnectionProperties(request, dbType);
        assertNotNull(updatedDatabase);
        Map<String, Object> actualConnection = ConnectionPropertiesUtils.getConnectionProperties(updatedDatabase.getConnectionProperties(), Role.ADMIN).get();
        assertEquals(request.getConnectionProperties(), actualConnection);

        verify(encryption, times(1)).deletePassword(database.getDatabase(), Role.ADMIN.toString());
        verify(encryption, times(1)).encryptPassword(database.getDatabase(), Role.ADMIN.toString());
        verify(databaseRegistryDbaasRepository, times(1)).saveAnyTypeLogDb(database);
    }

    @Test
    void testSuccessfullyUpdateConnectionPropertiesWhenDatabaseHasSeveralRoles() {
        String dbType = "mongodb";
        String databaseName = "database-name";
        String userName = "user-name";
        String newUserName = "test-username";
        String adapterId = "test-adapter-id";
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", NAMESPACE);
            put("microserviceName", "serviceOne");
        }};
        DatabaseRegistry database = createDatabase(classifier, dbType, adapterId, userName, databaseName);

        List<Map<String, Object>> listOfProperties = new ArrayList<>();
        String testUrl = "http://test-url";
        Map<String, Object> connectionAdmin = new HashMap<String, Object>() {{
            put("url", testUrl);
            put("username", userName);
            put(ROLE, Role.ADMIN.toString());
        }};
        listOfProperties.add(connectionAdmin);
        Map<String, Object> connectionRW = new HashMap<String, Object>() {{
            put("url", testUrl);
            put("username", userName);
            put(ROLE, "rw");
        }};
        listOfProperties.add(connectionRW);
        Map<String, Object> connectionRO = new HashMap<String, Object>() {{
            put("url", testUrl);
            put("username", userName);
            put(ROLE, "ro");
        }};
        listOfProperties.add(connectionRO);
        database.setConnectionProperties(listOfProperties);

        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newUserName);
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        when(adapter.identifier()).thenReturn(adapterId);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Collections.singletonList(adapter));
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, dbType)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        DatabaseRegistry updatedDatabase = dBaaService.updateDatabaseConnectionProperties(request, dbType);
        assertNotNull(updatedDatabase);

        Map<String, Object> actualConnectionAdmin = ConnectionPropertiesUtils.getConnectionProperties(updatedDatabase.getConnectionProperties(), Role.ADMIN.toString());
        assertEquals(request.getConnectionProperties(), actualConnectionAdmin);

        verify(encryption, times(1)).deletePassword(database.getDatabase(), Role.ADMIN.toString());
        verify(encryption, times(1)).encryptPassword(database.getDatabase(), Role.ADMIN.toString());
        verify(databaseRegistryDbaasRepository, times(1)).saveAnyTypeLogDb(database);
    }

    @Test
    void testSuccessfullyUpdateConnectionPropertiesForDifferentPhysicalDatabaseId() throws JsonProcessingException {
        String dbType = "mongodb";
        String databaseName = "database-name";
        String updatedDatabaseName = "new-database-name";
        String userName = "user-name";
        String updatedUserName = "test-username";
        String adapterId = "test-adapter-id";
        String updatedAdapterId = "new-test-adapter-id";
        String physicalDatabaseId = "test-phdbid";
        String updatedPhysicalDatabaseId = "new-test-phdbid";
        List<DbResource> resources = Arrays.asList(new DbResource("username", "newusername"), new DbResource("database", updatedDatabaseName));

        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "service");
        }};
        DatabaseRegistry database = createDatabase(classifier, dbType, adapterId, userName, databaseName);
        database.setPhysicalDatabaseId(physicalDatabaseId);
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, updatedUserName,
                updatedPhysicalDatabaseId, updatedDatabaseName, resources);
        DbaasAdapter adapter = Mockito.mock(DbaasAdapter.class);
        ExternalAdapterRegistrationEntry adapterRegistrationEntry = Mockito.mock(ExternalAdapterRegistrationEntry.class);
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);

        when(physicalDatabase.getAdapter()).thenReturn(adapterRegistrationEntry);
        when(adapterRegistrationEntry.getAdapterId()).thenReturn(updatedAdapterId);
        when(adapter.identifier()).thenReturn(adapterId);
        when(physicalDatabasesService.getAllAdapters()).thenReturn(Collections.singletonList(adapter));
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(updatedPhysicalDatabaseId)).thenReturn(physicalDatabase);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, dbType)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        DatabaseRegistry updatedDatabase = dBaaService.updateDatabaseConnectionProperties(request, dbType);
        assertNotNull(updatedDatabase);


        Map<String, Object> actualConnection = ConnectionPropertiesUtils.getConnectionProperties(updatedDatabase.getConnectionProperties(), Role.ADMIN).get();
        assertEquals(request.getConnectionProperties(), actualConnection);
        assertEquals(request.getDbName(), updatedDatabase.getName());
        assertEquals(request.getPhysicalDatabaseId(), updatedDatabase.getPhysicalDatabaseId());
        assertEquals(updatedAdapterId, updatedDatabase.getAdapterId());

        verify(encryption, times(1)).deletePassword(database.getDatabase(), Role.ADMIN.toString());
        verify(encryption, times(1)).encryptPassword(database.getDatabase(), Role.ADMIN.toString());
        verify(databaseRegistryDbaasRepository, times(1)).saveAnyTypeLogDb(database);
    }

    @Test
    void testUpdateConnectionPropertiesWithWrongResources() throws JsonProcessingException {
        String dbType = "mongodb";
        String databaseName = "database-name";
        String updatedDatabaseName = "new-database-name";
        String userName = "user-name";
        String updatedUserName = "test-username";
        String adapterId = "test-adapter-id";
        String updatedAdapterId = "new-test-adapter-id";
        String physicalDatabaseId = "test-phdbid";
        String updatedPhysicalDatabaseId = "new-test-phdbid";
        List<DbResource> resources = Arrays.asList(new DbResource("username", "newusername"));

        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "service");
        }};
        DatabaseRegistry database = createDatabase(classifier, dbType, adapterId, userName, databaseName);
        database.setPhysicalDatabaseId(physicalDatabaseId);
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, updatedUserName,
                updatedPhysicalDatabaseId, updatedDatabaseName, resources);
        ExternalAdapterRegistrationEntry adapterRegistrationEntry = Mockito.mock(ExternalAdapterRegistrationEntry.class);
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);

        when(physicalDatabase.getAdapter()).thenReturn(adapterRegistrationEntry);
        when(adapterRegistrationEntry.getAdapterId()).thenReturn(updatedAdapterId);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(physicalDatabasesService.getByPhysicalDatabaseIdentifier(updatedPhysicalDatabaseId)).thenReturn(physicalDatabase);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, dbType)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        assertThrows(InvalidUpdateConnectionPropertiesRequestException.class, () -> dBaaService.updateDatabaseConnectionProperties(request, dbType));
        List<DbResource> resourcesWithWrongName = new ArrayList<>();
        resourcesWithWrongName.addAll(resources);
        resourcesWithWrongName.add(new DbResource("database", "wrongName"));
        request.setResources(resourcesWithWrongName);
        assertThrows(InvalidUpdateConnectionPropertiesRequestException.class, () -> dBaaService.updateDatabaseConnectionProperties(request, dbType));

    }

    @Test
    void testUpdateConnectionPropertiesForNotExistingPhysicalDatabaseId() throws JsonProcessingException {
        String dbType = "mongodb";
        String databaseName = "database-name";
        String updatedDatabaseName = "new-darabase-name";
        String userName = "user-name";
        String updatedUserName = "test-username";
        String adapterId = "test-adapter-id";
        String physicalDatabaseId = "test-phdbid";
        String updatedPhysicalDatabaseId = "new-test-phdbid";
        List<DbResource> resources = List.of(new DbResource("username", "newusername"));

        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "service");
        }};
        DatabaseRegistry database = createDatabase(classifier, dbType, adapterId, userName, databaseName);
        database.setPhysicalDatabaseId(physicalDatabaseId);
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, updatedUserName,
                updatedPhysicalDatabaseId, updatedDatabaseName, resources);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(classifier, dbType)).thenReturn(Optional.of(database.getDatabaseRegistry().get(0)));

        assertThrows(UnregisteredPhysicalDatabaseException.class, () -> dBaaService.updateDatabaseConnectionProperties(request, dbType));
    }

    @Test
    void createdDatabaseV3() {
        String namespace = "namespace-test";
        String dbType = "test-type";
        String microserviceName = "test-microservice-name";
        DatabaseCreateRequestV3 createRequest = new DatabaseCreateRequestV3();
        createRequest.setType(dbType);
        createRequest.setClassifier(new HashMap<String, Object>() {{
            put("microserviceName", microserviceName);
        }});

        String adapterId = "test-adapter-id";
        DbaasAdapter testAdapter = Mockito.mock(DbaasAdapter.class);
        when(testAdapter.identifier()).thenReturn(adapterId);

        CreatedDatabaseV3 database = new CreatedDatabaseV3();
        List<Map<String, Object>> connectionProperties = List.of(new HashMap<String, Object>() {{
            put("username", "username");
            put("password", "password");
            put("role", Role.ADMIN);
        }});
        database.setConnectionProperties(connectionProperties);


        when(testAdapter.createDatabaseV3(eq(createRequest), eq(microserviceName))).thenReturn(database);

        ExternalAdapterRegistrationEntry adapterRegistrationEntry = Mockito.mock(ExternalAdapterRegistrationEntry.class);
        when(adapterRegistrationEntry.getAdapterId()).thenReturn(adapterId);
        PhysicalDatabase physicalDatabase = Mockito.mock(PhysicalDatabase.class);
        when(physicalDatabase.getPhysicalDatabaseIdentifier()).thenReturn(adapterId);
        when(physicalDatabase.getAdapter()).thenReturn(adapterRegistrationEntry);
        when(balancingRulesService.applyBalancingRules(eq(createRequest.getType()), eq(namespace), eq(microserviceName))).thenReturn(physicalDatabase);
        when(physicalDatabasesService.getAdapterById(eq(adapterId))).thenReturn(testAdapter);

        createRequest.setOriginService(microserviceName);
        createRequest.setUserRole(Role.ADMIN.toString());

        Optional<CreatedDatabaseV3> databaseOpt = dBaaService.createDatabase(createRequest, namespace, microserviceName);
        assertTrue(databaseOpt.isPresent());
        assertEquals(databaseOpt.get().getConnectionProperties(), connectionProperties);
        assertEquals(databaseOpt.get().getAdapterId(), adapterId);
    }

    @Test
    void updateFromOldClassifierToClassifierTestException() {
        String type = "test-type";
        SortedMap<String, Object> classifier1 = new TreeMap<>(testClassifier());

        SortedMap<String, Object> classifier2 = new TreeMap<>(testClassifier());
        classifier2.put("test_key", "test_value");
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByOldClassifierAndType(classifier1, type)).thenReturn(null);
        Assertions.assertThrows(NotFoundException.class, () -> dBaaService.updateFromOldClassifierToClassifier(classifier1, classifier2, type, false));
    }

    @Test
    void updateFromOldClassifierToClassifierTest() {

        SortedMap<String, Object> classifier1 = new TreeMap<>(testClassifier());
        SortedMap<String, Object> classifier2 = new TreeMap<>(testClassifier());
        classifier2.put("test_key", "test_value");

        String type = "test-type";
        DatabaseRegistry db = createDatabase(classifier1, type, "adapter-id", "username", "dbname");
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        Mockito.when(databaseRegistryDbaasRepository.getDatabaseByOldClassifierAndType(classifier1, type)).thenReturn(db.getDatabaseRegistry().get(0));

        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any())).thenReturn(null);

        DatabaseRegistry db2 = dBaaService.updateFromOldClassifierToClassifier(classifier1, classifier2, type, false);
        assertEquals(classifier2, db2.getClassifier());
    }

    @Test
    void findDatbasesWithFailedMigrationTest() {
        String type = "test-type";
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("V3_TRANSFORMATION", "fail");
        SortedMap<String, Object> oldClassifier = new TreeMap<>(testClassifier());
        DatabaseRegistry db = createDatabase(classifier, type, "adapter-id", "username", "dbname", oldClassifier);
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(databaseRegistryDbaasRepository.findAnyLogDbRegistryTypeByNamespace(NAMESPACE)).thenReturn(db.getDatabaseRegistry());
        List<FailedTransformationDatabaseResponse> databasesWithFailedMigration = dBaaService.findDatabasesWithFailedMigration(NAMESPACE);
        assertEquals(db.getOldClassifier(), databasesWithFailedMigration.get(0).getClassifier());
        assertEquals(db.getType(), databasesWithFailedMigration.get(0).getType());
        assertEquals(db.getDatabaseRegistry().get(0).getId(), databasesWithFailedMigration.get(0).getId());
    }

    @Test
    void processConnectionPropertiesV3WithPhysId() {
        DatabaseRegistry database = createDatabase(testClassifier(), "postgresql", "adapter-id-1", "username-1", "dbname-1");
        database.setPhysicalDatabaseId("phys-id-1");

        DatabaseResponseV3ListCP databaseResponseV3ListCP = dBaaService.processConnectionPropertiesV3(database.getDatabaseRegistry().get(0));

        assertEquals("phys-id-1", databaseResponseV3ListCP.getPhysicalDatabaseId());
        verify(physicalDatabasesService, never()).getByAdapterId(any());
    }

    @Test
    void testCreateDatabaseWithForbiddenDefaultDatabase() {
        String namespace = "namespace-test";
        String dbType = "test-type";
        String microserviceName = "test-microservice-name";
        DatabaseCreateRequestV3 createRequest = new DatabaseCreateRequestV3();
        createRequest.setType(dbType);
        createRequest.setClassifier(new HashMap<String, Object>() {{
            put("microserviceName", microserviceName);
        }});

        when(balancingRulesService.applyBalancingRules(eq(createRequest.getType()), eq(namespace), eq(microserviceName))).thenThrow(NoBalancingRuleException.class);

        createRequest.setOriginService(microserviceName);
        createRequest.setUserRole(Role.ADMIN.toString());

        assertThrows(NoBalancingRuleException.class, () -> dBaaService.createDatabase(createRequest, namespace, microserviceName));
    }

    @Test
    void findDatabaseByOldClassifierAndType() {
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        SortedMap<String, Object> classifier = testClassifier();
        String type = "postgresql";
        Assertions.assertNull(dBaaService.findDatabaseByOldClassifierAndType(classifier, type, false));
    }

    @Test
    void findDatabaseByOldClassifierAndTypeDetach() {
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        SortedMap<String, Object> classifier = testClassifier();
        String type = "postgresql";
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        when(databaseRegistryDbaasRepository.getDatabaseByOldClassifierAndType(classifier, type)).thenReturn(databaseRegistry);
        DatabaseRegistry returnedDbr = dBaaService.findDatabaseByOldClassifierAndType(classifier, type, false);
        verify(entityManager).detach(any());
        Assertions.assertNotNull(returnedDbr);
        Assertions.assertNull(returnedDbr.getId());
    }

    @Test
    void detach() {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        databaseRegistry.setDatabase(database);
        dBaaService.detach(databaseRegistry);
        Mockito.verify(entityManager).detach(databaseRegistry);
        Mockito.verify(entityManager).detach(database);
    }

    @Test
    void processConnectionProperties() {
        String type = "postgresql";
        DatabaseRegistry database = createDatabase(testClassifier(), type, "adapter", "username", "dbName");
        DatabaseResponse databaseResponse = dBaaService.processConnectionProperties(database);
        Assertions.assertEquals(database.getId(), databaseResponse.getId());
    }

    @Test
    void testUpdateDatabaseConnectionPropertiesAndResourcesById() {
        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        when(logicalDbDbaasRepository.getDatabaseDbaasRepository()).thenReturn(databaseDbaasRepository);
        SuccessRegistrationV3 successRegistrationV3 = new SuccessRegistrationV3();

        String type = "postgresql";
        DatabaseRegistry database = createDatabase(testClassifier(), type, "adapter", "username", "dbName");
        successRegistrationV3.setId(database.getDatabase().getId());
        successRegistrationV3.setConnectionProperties(database.getConnectionProperties());
        successRegistrationV3.setResources(database.getResources());
        when(databaseDbaasRepository.findById(database.getDatabase().getId())).thenReturn(Optional.of(database.getDatabase()));
        dBaaService.updateDatabaseConnectionPropertiesAndResourcesById(successRegistrationV3.getId(), successRegistrationV3.getConnectionProperties(), successRegistrationV3.getResources());
        verify(encryption).encryptPassword(any(Database.class));
    }

    @Test
    void testShareDbToNamespace_NotSharedYet() {
        String targetNamespace = "target";
        Database database = new Database();
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put(MICROSERVICE_NAME, "ms");
        classifier.put(Constants.NAMESPACE, "old_ns");
        DatabaseRegistry databaseRegistry = new DatabaseRegistry(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType("postgresql");
        database.setDatabaseRegistry(new ArrayList<>(List.of(databaseRegistry)));

        when(logicalDbDbaasRepository.getDatabaseRegistryDbaasRepository()).thenReturn(databaseRegistryDbaasRepository);
        DatabaseRegistry sharedRegistry = dBaaService.shareDbToNamespace(databaseRegistry, targetNamespace);
        assertEquals(targetNamespace, sharedRegistry.getNamespace());
        assertEquals(database, sharedRegistry.getDatabase());
        assertEquals(2, database.getDatabaseRegistry().size());
        verify(databaseRegistryDbaasRepository, times(1)).saveAnyTypeLogDb(sharedRegistry);
    }

    @Test
    void testShareDbToNamespace_AlreadyShared() {
        String targetNamespace = "target";
        Database database = new Database();
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put(MICROSERVICE_NAME, "ms");
        classifier.put(Constants.NAMESPACE, targetNamespace);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry(database);
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType("postgresql");
        database.setDatabaseRegistry(new ArrayList<>(List.of(databaseRegistry)));

        DatabaseRegistry sharedRegistry = dBaaService.shareDbToNamespace(databaseRegistry, targetNamespace);
        assertEquals(databaseRegistry, sharedRegistry);
        assertEquals(1, database.getDatabaseRegistry().size());
        verify(databaseRegistryDbaasRepository, times(0)).saveAnyTypeLogDb(sharedRegistry);
    }

    @Test
    void testProvidePasswordFor() {
        Database db = Mockito.mock(Database.class);
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put(PASSWORD_FIELD, "12345");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        when(db.getConnectionProperties()).thenReturn(Arrays.asList(connectionProperties));

        String password = dBaaService.providePasswordFor(db, Role.ADMIN.toString());
        assertEquals("12345", password);
    }

    private UpdateConnectionPropertiesRequest createUpdateConnectionPropertiesRequest(SortedMap<String, Object> classifier, String username) {
        String testUrl = "http://test-url";
        Map<String, Object> connection = new HashMap<String, Object>() {{
            put("url", testUrl);
            put("username", username);
            put(ROLE, Role.ADMIN.toString());
        }};
        UpdateConnectionPropertiesRequest request = new UpdateConnectionPropertiesRequest();
        request.setClassifier(classifier);
        request.setConnectionProperties(connection);
        return request;
    }

    private UpdateConnectionPropertiesRequest createUpdateConnectionPropertiesRequest(SortedMap<String, Object> classifier,
                                                                                      String username,
                                                                                      String physicalDatabaseId, String databaseName,
                                                                                      List<DbResource> resources) {
        UpdateConnectionPropertiesRequest updateConnectionPropertiesRequest = createUpdateConnectionPropertiesRequest(classifier, username);
        updateConnectionPropertiesRequest.setPhysicalDatabaseId(physicalDatabaseId);
        updateConnectionPropertiesRequest.setDbName(databaseName);
        updateConnectionPropertiesRequest.setResources(resources);
        return updateConnectionPropertiesRequest;
    }

    private Matcher<DatabaseRegistry> checkNewlyCreated(DatabaseRegistry newDb) {
        return new TypeSafeMatcher<DatabaseRegistry>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("new logical database and old database should not be equal");
            }

            @Override
            protected boolean matchesSafely(DatabaseRegistry exitedDb) {
                return !exitedDb.getId().equals(newDb.getId())
                        && !exitedDb.getClassifier().equals(newDb.getClassifier())
                        && !exitedDb.getAdapterId().equals(newDb.getAdapterId())
                        && !exitedDb.getConnectionProperties().equals(newDb.getConnectionProperties())
                        && !exitedDb.getResources().equals(newDb.getResources())
                        && !exitedDb.getName().equals(newDb.getName())
                        && !exitedDb.getTimeDbCreation().equals(newDb.getTimeDbCreation());
            }
        };
    }

    private CreatedDatabaseV3 getCreatedDatabase(String username, String newAdapterId, String newDbName, String password) {
        CreatedDatabaseV3 createdDatabase = new CreatedDatabaseV3();
        createdDatabase.setAdapterId(newAdapterId);
        createdDatabase.setName(newDbName);
        createdDatabase.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put("username", username);
            put("password", password);
        }}));
        createdDatabase.setResources(Collections.singletonList(new DbResource("username", username)));
        return createdDatabase;
    }


    private SortedMap<String, Object> testClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test-microservice");
        classifier.put("isServiceDb", true);
        classifier.put("namespace", NAMESPACE);
        return classifier;
    }
}
