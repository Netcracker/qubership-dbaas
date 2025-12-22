package com.netcracker.it.dbaas.test;

import com.google.common.collect.ImmutableMap;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.response.SuccessfulRestoreUsersResponse;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class DatabasesAPIV3IT extends AbstractIT {

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    private DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder getPrimaryCreateDbRequestBuilder(String type, String testClassifierValue) {
        return DatabaseCreateRequestV3.builder(new ClassifierBuilder().test(testClassifierValue).build(), type);
    }

    private DatabaseCreateRequestV3 getSimplePostgresCreateRequest(String testClassifierValue) {
        return getPrimaryCreateDbRequestBuilder(POSTGRES_TYPE, testClassifierValue)
                .namePrefix("dbaas_autotests")
                .originService(TEST_MICROSERVICE_NAME)
                .build();
    }

    private DatabaseCreateRequestV3 getSimpleMongoCreateRequest(String testClassifierValue) {
        return getPrimaryCreateDbRequestBuilder(MONGODB_TYPE, testClassifierValue)
                .namePrefix("dbaas_autotests")
                .originService(TEST_MICROSERVICE_NAME)
                .build();
    }

    @Test
    public void mongoTestDatabaseCreatedAndConnecting() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");
        log.info("Create database");
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleMongoCreateRequest("dbaas_auto_test_1"), 201);

        assertThat(created_1, inNamespace(TEST_NAMESPACE));
        assertFalse(created_1.isExternallyManageable());
        log.info("Check connection to created database");
        helperV3.checkConnectionMongo(created_1);
    }

    @Test
    public void testDbsCleanedPostgres() throws IOException, SQLException {
        log.info("Create database");
        DatabaseResponse postgresDB = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);

        log.info("Check connection to postgres database");
        helperV3.checkConnectionPostgres(postgresDB);

        assertThat(postgresDB, inNamespace(TEST_NAMESPACE));
        log.info("Check connection to deleted databases");
        cleanDbs();
        Assertions.assertThrows(CannotConnect.class, () ->
                Failsafe.with(DEFAULT_RETRY_POLICY).run(() ->
                        helperV3.checkConnectionPostgres(postgresDB)));
    }

    @Test
    void testDeleteAllNotExistingDbs() throws IOException {
        String responseBody = helperV3.deleteDatabases(DbaasHelperV3.DATABASES_V3, helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE,
                HttpStatus.SC_OK);
        assertEquals("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources", responseBody);
    }


    @Test
    void testDeleteAllV3NotExistingDbs() throws IOException {
        String responseBody = helperV3.deleteDatabases(DbaasHelperV3.DATABASES_V3 + "/deleteall", helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE,
                HttpStatus.SC_OK);
        assertEquals("namespace " + TEST_NAMESPACE + " doesn't contain any databases and namespace specific resources", responseBody);
    }


    @Test
    public void testDbsCleanedMongo() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");
        log.info("Create database");
        DatabaseResponse mongoDB = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                getSimpleMongoCreateRequest("dbaas_auto_test_2"), 201);
        log.info("Check connection to mongo database");
        helperV3.checkConnectionMongo(mongoDB);
        assertThat(mongoDB, inNamespace(TEST_NAMESPACE));
        log.info("Check connection to deleted databases");
        cleanDbs();
        Assertions.assertThrows(CannotConnect.class, () ->
                Failsafe.with(DEFAULT_RETRY_POLICY).run(() ->
                        helperV3.checkConnectionMongo(mongoDB, true)));
    }

    @Test
    public void postgresTestDatabaseCreatedAndConnecting() throws IOException, SQLException {
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);
        log.info("Create database");
        assertThat(created_1, inNamespace(TEST_NAMESPACE));
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(created_1);
    }

    @Test
    public void postgresTestAsyncDatabaseCreatedAndConnecting() throws IOException, SQLException {
        String requestUrl = String.format(DATABASES_V3_ASYNC, TEST_NAMESPACE);
        Request createDbRequest = helperV3.createRequest(requestUrl, helperV3.getClusterDbaAuthorization(), getSimplePostgresCreateRequest("dbaas_auto_test_1"), "PUT");
        log.info("request: {}", createDbRequest);
        Response response = helperV3.executeRequest(createDbRequest, 202);
        response.close();

        DatabaseResponse createdResponse = Failsafe.with(AWAIT_DB_CREATION_RETRY_POLICY).get(() ->
                helperV3.createDatabase(requestUrl, getSimplePostgresCreateRequest("dbaas_auto_test_1"), 200));
        log.info("Create database");
        assertThat(createdResponse, inNamespace(TEST_NAMESPACE));
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(createdResponse);
    }

    @Test
    public void postgresTestDatabaseCreatedAndConnectionContainsTLS() throws IOException, SQLException {
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);
        assumeTrue(helperV3.isTLSEnabledInDatabase(POSTGRES_TYPE, created_1.getPhysicalDatabaseId()), "Skip test because TLS is disabled in database");
        log.info("Create database");
        Map<String, Object> connectionProperties = created_1.getConnectionProperties();
        assertTrue(connectionProperties.containsKey("tls"));
        helperV3.checkConnectionPostgres(created_1);
    }

    @Test
    @Tag("Smoke")
    public void createMongoDbInRegisteredPhysDb() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");
        List<DatabaseResponse> createdMongoDb = createDatabaseInRegisteredPhysDb("mongodb");
        log.debug("Was created {} mongo database one in each cluster", createdMongoDb.size());
        log.debug("Check connect on created mongo databases");
        createdMongoDb.forEach(database -> {
            if (database.getPhysicalDatabaseId().equals("defaultMongoAdapter")) {
                helperV3.checkConnectionMongo(database, false);
            } else {
                helperV3.checkConnectNotDefaultMongoCluster(database, false);
            }
        });
        cleanDbs();
        log.debug("Check that mongo databases was deleted and connect does not exists");
        createdMongoDb.forEach(database -> {
            log.info("Check that database {} was deleted", database);
            try {
                if (database.getPhysicalDatabaseId().equals("defaultMongoAdapter")) {
                    helperV3.checkConnectionMongo(database, true);
                    fail(String.format("Database %s should be deleted not it was not", database.getName()));
                } else {
                    helperV3.checkConnectNotDefaultMongoCluster(database, true);
                    fail(String.format("Database %s should be deleted not it was not", database));
                }
            } catch (CannotConnect | MongoTimeoutException | MongoSecurityException e) {
                assertFalse(e.getMessage().equals(String.format("Database %s should be deleted not it was not", database)) ||
                        e.getMessage().equals(String.format("Database %s should be deleted not it was not", database.getName())));
                log.debug("Database {} was successfully deleted", database);
            }
        });
    }

    @Test
    public void testDeleteAllDatabasesInNamespaceUsingOldAPI() throws Exception {
        checkDatabaseDeletion(DATABASES_V3);
    }

    @Test
    public void testDeleteAllDatabasesInNamespaceUsingNewAPI() throws Exception {
        checkDatabaseDeletion(DATABASES_DELETE_V3);
    }

    private void checkDatabaseDeletion(String URL) throws Exception {
        String clusterDbaAuthorization = helperV3.getClusterDbaAuthorization();
        log.info("Create database");
        helperV3.createDatabase(clusterDbaAuthorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null);
        List<DatabaseV3> databasesInNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
        assertThat(databasesInNamespace, hasSize(1));

        helperV3.deleteDatabases(URL, clusterDbaAuthorization, TEST_NAMESPACE, HttpStatus.SC_OK);
        databasesInNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
        assertThat(databasesInNamespace, hasSize(0));
    }

    @Test
    @Tag("Smoke")
    public void createPostgresDbInRegisteredPhysDb() throws IOException {
        List<DatabaseResponse> createdPostgresDb = createDatabaseInRegisteredPhysDb("postgresql");
        log.debug("Was created {} postgres database one in each clusters", createdPostgresDb.size());
        log.debug("Check connect on created postgres databases");
        createdPostgresDb.forEach(database -> {
            try {
                if (database.getPhysicalDatabaseId().equals("defaultPostgresAdapter")) {
                    helperV3.checkConnectionPostgres(database);
                } else {
                    helperV3.checkConnectNotDefaultPostgresCluster(database);
                }
            } catch (SQLException | CannotConnect | IOException e) {
                fail("Error occurred during connect to db " + database + " : " + e.getMessage());
            }
        });
        cleanDbs();
        log.debug("Check that postgres databases was deleted and connect does not exists");
        createdPostgresDb.forEach(database -> {
            try {
                log.info("Check that db {} was removed", database);
                if (database.getPhysicalDatabaseId().equals("defaultPostgresAdapter")) {
                    helperV3.checkConnectionPostgres(database);
                    fail(String.format("Database %s should be deleted not it was not", database));
                } else {
                    helperV3.checkConnectNotDefaultPostgresCluster(database);
                    fail(String.format("Database %s should be deleted not it was not", database.getName()));
                }
            } catch (SQLException | CannotConnect | IOException e) {
                assertFalse(e.getMessage().equals(String.format("Database %s should be deleted not it was not", database)) ||
                        e.getMessage().equals(String.format("Database %s should be deleted not it was not", database.getName())));
                log.debug("Database {} was successfully deleted", database);
            }
        });
    }

    @NotNull
    private List<DatabaseResponse> createDatabaseInRegisteredPhysDb(String typeDb) throws IOException {
        RegisteredPhysicalDatabasesDTOV3 registeredPhysicalDatabases = helperV3.getRegisteredPhysicalDatabases(typeDb, helperV3.getClusterDbaAuthorization(), 200);
        List<DatabaseResponse> createdDatabases = createDatabaseInOtherPhysicalDatabases(registeredPhysicalDatabases, typeDb);
        int expectCountCreatedDb = registeredPhysicalDatabases.getIdentified().size();
        assertThat(createdDatabases, hasSize(expectCountCreatedDb));
        long countAdapters = createdDatabases.stream().map(DatabaseResponse::getPhysicalDatabaseId).distinct().count();
        assertEquals(countAdapters, createdDatabases.size());
        return createdDatabases;
    }

    private List<DatabaseResponse> createDatabaseInOtherPhysicalDatabases(RegisteredPhysicalDatabasesDTOV3 physicalDatabases, String dbType) throws IOException {
        List<DatabaseResponse> createdDb = new ArrayList<>();
        for (String physicalDatabaseId : physicalDatabases.getIdentified().keySet()) {
            log.debug("Create database in cluster");
            DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder request = getPrimaryCreateDbRequestBuilder(dbType, "physical database identifier " + physicalDatabaseId)
                    .physicalDatabaseId(physicalDatabaseId)
                    .namePrefix("dbaas_autotests");
            request.originService(TEST_MICROSERVICE_NAME);
            request.physicalDatabaseId(physicalDatabaseId);
            request.originService(TEST_MICROSERVICE_NAME);
            DatabaseResponse clusterDb = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), request.build(), 201);
            createdDb.add(clusterDb);
        }
        return createdDb;

    }


    @Test
    public void mongoTestDuplicateNotCreated() throws IOException {
        assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");
        testDuplicateNotCreated(MONGODB_TYPE);
    }

    @Test
    public void postgresTestDuplicateNotCreated() throws IOException {
        testDuplicateNotCreated(POSTGRES_TYPE);
    }

    @Test
    public void postgresTestInitialScript_LO() throws IOException {
        log.info("Create databases");
        log.info("Create first");
        DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder request = getPrimaryCreateDbRequestBuilder(POSTGRES_TYPE, "check InitialScript_LO")
                .initScriptIdentifiers(Collections.singletonList("CREATE_LO_EXTENSION_IF_NOT_EXISTS"))
                .originService(TEST_MICROSERVICE_NAME)
                .namePrefix("dbaas_autotests");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), request.build(), 201);
    }

    @Test
    @Tag("Smoke")
    public void saveExternalDatabase() {
        ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", TEST_NAMESPACE);
        ExternalDatabaseResponseV3 externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 201);
        assertNotNull(externalDatabaseResponse.getId());
        assertTrue(externalDatabaseResponse.isExternallyManageable());
        String type = externalDatabaseResponse.getType();
        assertEquals("tarantool", type);
        Object password = externalDatabaseResponse.getConnectionProperties().getFirst().get("password");
        assertEquals("password", password);
        helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 200);
    }

    @Test
    public void updateExternalDatabaseConnectionProperties() {
        Map<String, Object> firstConnectionProperty = new HashMap<>();
        firstConnectionProperty.put("role", Role.ADMIN.getRoleValue());
        firstConnectionProperty.put("username", "external-user-test");
        firstConnectionProperty.put("password", "password");
        List<Map<String, Object>> connectionProperties = new ArrayList<>();
        connectionProperties.add(firstConnectionProperty);
        ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", TEST_NAMESPACE);
        ExternalDatabaseResponseV3 externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, connectionProperties, "tarantool", false, 201);
        Assertions.assertEquals(connectionProperties, externalDatabaseResponse.getConnectionProperties());

        Map<String, Object> newConnectionProperty = new HashMap<>();
        newConnectionProperty.put("role", Role.ADMIN.getRoleValue());
        newConnectionProperty.put("username", "external-user-test2");
        newConnectionProperty.put("password", "password2");
        connectionProperties.add(newConnectionProperty);
        externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, connectionProperties, "tarantool", true, 201);
        Assertions.assertEquals(connectionProperties, externalDatabaseResponse.getConnectionProperties());
        assertEquals(connectionProperties.get(1).get("password"), externalDatabaseResponse.getConnectionProperties().get(1).get("password"));

        externalDatabaseResponse = helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, new ArrayList<>(), "tarantool", false, 200);
        Assertions.assertEquals(connectionProperties, externalDatabaseResponse.getConnectionProperties());

        helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, new ArrayList<>(), "tarantool", true, 500);
    }

    @Test
    public void getListDatabasesAnyType() throws IOException {
        ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external_any_type", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", TEST_NAMESPACE);
        helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 201);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_any_type"), 201);
        List<DatabaseV3> databasesByNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
        List<DatabaseV3> databasesByExternalClassifier = filterByClassifierField(databasesByNamespace, "typeLogicalDb", "external_any_type");
        log.info("external databases:{}", databasesByExternalClassifier);
        List<DatabaseV3> databasesByAutotestClassifier = filterByClassifierField(databasesByNamespace, "dbaas_auto_test", "dbaas_auto_test_any_type");
        log.info("internal databases:{}", databasesByAutotestClassifier);
        databasesByExternalClassifier.addAll(databasesByAutotestClassifier);
        List<DatabaseV3> notMarkedForDrop = filterByMarkNotForDrop(databasesByExternalClassifier);
        log.info("databases not marked for the drop:{}", notMarkedForDrop);
        assertThat(notMarkedForDrop, hasSize(2));
        Map<String, List<DatabaseV3>> databaseMap = notMarkedForDrop.stream().collect(Collectors.groupingBy(DatabaseV3::getType));
        assertThat(databaseMap.get(POSTGRES_TYPE), hasSize(1));
        assertThat(databaseMap.get("tarantool"), hasSize(1));
        assertFalse(databaseMap.get(POSTGRES_TYPE).getFirst().isExternallyManageable());
        assertTrue(databaseMap.get("tarantool").getFirst().isExternallyManageable());
    }

    private List<DatabaseV3> filterByClassifierField(List<DatabaseV3> databases, String key, String value) {
        return databases.stream().filter(database -> database.getClassifier().containsKey(key) && database.getClassifier().get(key).equals(value)
        ).collect(Collectors.toList());
    }

    private List<DatabaseV3> filterByMarkNotForDrop(List<DatabaseV3> databases) {
        return databases.stream().filter(database -> !database.getClassifier().containsKey("MARKED_FOR_DROP")).collect(Collectors.toList());
    }

    @Test
    @Tag("Smoke")
    public void getExternalDatabasesByClassifier() throws IOException {
        ImmutableMap<String, Object> classifier = ImmutableMap.of("typeLogicalDb", "external", "microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", "namespace", TEST_NAMESPACE);
        helperV3.saveExternalDatabase(EXTERNALLY_MANAGEABLE_V3, classifier, "tarantool", 201);
        DatabaseResponse externalDatabase = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, "tarantool", 200);
        assertNull(externalDatabase.getId());
        assertTrue(externalDatabase.isExternallyManageable());
    }

    private void testDuplicateNotCreated(String type) throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        log.info("Create databases");
        log.info("Create first");
        DatabaseResponse created_1 = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, type, null, TEST_NAMESPACE, false);
        assertThat(created_1, inNamespace(TEST_NAMESPACE));
        log.info("Create second");
        DatabaseResponse created_2 = helperV3.createDatabase(authorization, "dbaas_auto_test_2", 201, type, null, TEST_NAMESPACE, false);
        assertThat(created_2, inNamespace(TEST_NAMESPACE));
        log.info("Create second copy");
        DatabaseResponse duplicate_2 = helperV3.createDatabase(authorization, "dbaas_auto_test_2", 200, type, null, TEST_NAMESPACE, false);
        assertThat(duplicate_2, inNamespace(TEST_NAMESPACE));
        log.info("Check created databases");
        assertEquals(created_2.getName(), duplicate_2.getName());
        log.info("Check duplicate database pojo does not contain id");
        assertNull(duplicate_2.getId());
    }

    @Test
    public void testGetNotExistingDBs() throws IOException {
        this.testGetNotExistingDB(MONGODB_TYPE);
        this.testGetNotExistingDB(POSTGRES_TYPE);
    }

    @Test
    public void testDBCreationWithSettings() throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        Map<String, Object> settingsMap = Collections.singletonMap("pgExtensions", Collections.singletonList("uuid-ossp"));
        log.info("Create database");
        DatabaseResponse databaseWithSettings = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, settingsMap);
        log.info("Check settings");
        assertEquals(settingsMap, databaseWithSettings.getSettings());
    }

    @Test
    public void testSettingsUpdateCRUDScenarios() throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        log.info("Create database");
        DatabaseResponse initialDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false);
        assertNull(initialDatabase.getSettings());

        //create : oldExtensions=[uuid-ossp], newExtensions=[bloom, uuid-ossp, seg]
        Map<String, Object> settingsMap = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "seg"));
        DatabaseResponse updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        assertEquals(settingsMap, updatedDatabase.getSettings());
        assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

        //read : oldExtensions=[bloom, uuid-ossp, seg], newExtensions=[bloom, uuid-ossp, seg]
        updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        assertEquals(settingsMap, updatedDatabase.getSettings());
        assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

        //update (add new extension):  oldExtensions=[bloom, uuid-ossp, seg], newExtensions=[bloom, uuid-ossp, plpqsql], actual=[bloom, uuid-ossp, seg, plpgsql]
        settingsMap = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "plpgsql"));
        updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        Map<String, Object> expectedSettings = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "seg", "plpgsql"));
        assertEquals(expectedSettings, updatedDatabase.getSettings());
        assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

        //update (add new extension):  [bloom, uuid-ossp, seg, plpgsql], newExtensions=[plpqsql], actual=[bloom, uuid-ossp, seg, plpgsql]
        settingsMap = Collections.singletonMap("pgExtensions", List.of("plpgsql"));
        updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        expectedSettings = Collections.singletonMap("pgExtensions", Arrays.asList("bloom", "uuid-ossp", "seg", "plpgsql"));
        assertEquals(expectedSettings, updatedDatabase.getSettings());
        assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());

        //delete : oldExtensions=[bloom, uuid-ossp, seg, plpgsql], dropExtensions=[bloom, uuid-ossp, seg], actual=[plpqsql]
        settingsMap = Map.of("pgExtensions", Collections.singletonList("plpgsql"), "dropExtensions", List.of("bloom", "uuid-ossp", "seg"));
        updatedDatabase = helperV3.updateDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        expectedSettings = Collections.singletonMap("pgExtensions", List.of("plpgsql"));
        assertEquals(expectedSettings, updatedDatabase.getSettings());
        assertEquals(initialDatabase.getClassifier(), updatedDatabase.getClassifier());
    }

    @Test
    public void testUpdateSettingsExtremeCases() throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        log.info("Create database");
        Map<String, Object> settingsMap = Collections.singletonMap("pgExtensions", Collections.singletonList("uuid-ossp"));
        DatabaseResponse databaseWithSettings = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false, settingsMap);
        assertEquals(settingsMap, databaseWithSettings.getSettings());

        //null
        settingsMap = null;
        DatabaseResponse updatedDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        assertEquals(databaseWithSettings.getSettings(), updatedDatabase.getSettings());
        assertEquals(databaseWithSettings.getClassifier(), updatedDatabase.getClassifier());

        // empty settings
        settingsMap = Collections.emptyMap();
        updatedDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        assertEquals(databaseWithSettings.getSettings(), updatedDatabase.getSettings());
        assertEquals(databaseWithSettings.getClassifier(), updatedDatabase.getClassifier());

        // empty extensions
        settingsMap = Collections.singletonMap("dropExtensions", List.of("uuid-ossp"));
        updatedDatabase = helperV3.createDatabase(authorization, "dbaas_auto_test_1", 200, POSTGRES_TYPE, null, TEST_NAMESPACE, false, null, "dbaas_autotests", settingsMap);
        assertEquals(Collections.singletonMap("pgExtensions", Collections.EMPTY_LIST), updatedDatabase.getSettings());
        assertEquals(databaseWithSettings.getClassifier(), updatedDatabase.getClassifier());
    }

    @Test
    @Tag("Smoke")
    public void testGetAllPhysicalDBs() throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        Map<String, PhysicalDatabaseRegistrationResponseDTOV3> registeredPhysicalDatabases =
                helperV3.getRegisteredPhysicalDatabases("all", authorization, 200)
                        .getIdentified();
        assertFalse(registeredPhysicalDatabases.isEmpty(), "No physical db returned");
        List<PhysicalDatabaseRegistrationResponseDTO> postgresqlDbs = registeredPhysicalDatabases.values().stream().filter(db -> Objects.equals(db.getType(), "postgresql")).collect(Collectors.toList());
        assertFalse(postgresqlDbs.isEmpty(), "At least 1 postgresql db must be returned");
    }

    @Test
    public void checkResponseWhenDatabaseIsNotCreatedYet() throws InterruptedException, ExecutionException {
        Integer[] expectedAnswers = {201, 202};

        String requestId = DbaasHelperV3.getRequestId();
        Callable<DatabaseResponse> requestToCreateDb = () -> {
            DbaasHelperV3.setRequestId(requestId);
            return helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), expectedAnswers);
        };
        List<Callable<DatabaseResponse>> callableTasks = new ArrayList<>();
        callableTasks.add(requestToCreateDb);
        callableTasks.add(requestToCreateDb);
        callableTasks.add(requestToCreateDb);

        List<Future<DatabaseResponse>> receivedDatabases;
        try (ExecutorService es = Executors.newFixedThreadPool(3)) {
            receivedDatabases = es.invokeAll(callableTasks);
        }
        for (Future<DatabaseResponse> db : receivedDatabases) {
            DatabaseResponse currentDatabase = db.get();
            assertThat(currentDatabase.getConnectionProperties(), is(notNullValue()));
        }
    }

    @Test
    @Tag("Smoke")
    void UserCreatedAndConnectingTest() throws IOException, SQLException {
        String logicalUserId = "test-service";
        DatabaseCreateRequestV3 createDbRequest = getSimplePostgresCreateRequest("dbaas_auto_test_1");
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);
        log.info("Create database");
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(created_1);

        Map<String, Object> classifier = createDbRequest.getClassifier();
        GetOrCreateUserRequest getOrCreateUserRequest = new GetOrCreateUserRequest(classifier, logicalUserId,
                POSTGRES_TYPE, null, "test", Role.ADMIN.getRoleValue());
        log.info("Creating user");
        GetOrCreateUserResponse user = helperV3.getOrCreateUser(USER_API_URL_V3, getOrCreateUserRequest, 201);
        log.info("Check connections to user");
        helperV3.checkConnectionPostgresUser(user, "test", "test");

        log.info("Rotate user password");
        UserOperationRequest userOperationRequest = new UserOperationRequest();
        userOperationRequest.setUserId(user.getUserId());
        Map<String, Object> newConnectionProperties = helperV3.rotateUserPassword(USER_API_URL_V3 + "/rotate-password",
                        userOperationRequest,
                        200)
                .getConnectionProperties();
        assertNotEquals(user.getConnectionPropertyAsString("password"), newConnectionProperties.get("password"));

        log.info("Check connections to user with new password");
        GetOrCreateUserResponse userWithNewPassword = new GetOrCreateUserResponse();
        userWithNewPassword.setConnectionProperties(newConnectionProperties);
        helperV3.checkConnectionPostgresUser(userWithNewPassword, "test", "test");

        log.info("Delete created user");
        helperV3.deleteUser(USER_API_URL_V3, userOperationRequest, 200);
        assertThrows(CannotConnect.class, () -> helperV3.checkConnectionPostgresUser(userWithNewPassword, "test", "test"));
    }

    @Test
    void getAllDatabasesInNamespaceWithCreatedUsersTest() throws IOException, SQLException {
        String logicalUserId = "test-service";
        DatabaseCreateRequestV3 createDbRequest = getSimplePostgresCreateRequest("dbaas_auto_test_1");
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);
        log.info("Create database");
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(created_1);

        Map<String, Object> classifier = createDbRequest.getClassifier();
        GetOrCreateUserRequest getOrCreateUserRequest = new GetOrCreateUserRequest(classifier, logicalUserId,
                POSTGRES_TYPE, null, "test", Role.ADMIN.getRoleValue());
        log.info("Creating user");
        GetOrCreateUserResponse user = helperV3.getOrCreateUser(USER_API_URL_V3, getOrCreateUserRequest, 201);

        log.info("Check connections to user");
        helperV3.checkConnectionPostgresUser(user, "test", "test");

        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/databases/list", TEST_NAMESPACE),
                helperV3.getClusterDbaAuthorization(),
                null,
                "GET");
        List<DatabaseV3> databases = Arrays.asList(helperV3.executeRequest(request, DatabaseV3[].class, 200));
        assertEquals(1, databases.size());
        Optional<Map<String, Object>> connections = databases.getFirst().getConnectionProperties().stream()
                .filter(u -> u.get("logicalUserId") != null && u.get("logicalUserId").equals(logicalUserId))
                .findFirst();
        assertTrue(connections.isPresent());
    }

    @Test
    void UserDeletedAfterDatabaseDeletionTest() throws IOException, SQLException {
        String logicalUserId = "test-service";
        DatabaseCreateRequestV3 createDbRequest = getSimplePostgresCreateRequest("dbaas_auto_test_1");
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);
        log.info("Create database");
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(created_1);

        Map<String, Object> classifier = createDbRequest.getClassifier();
        GetOrCreateUserRequest getOrCreateUserRequest = new GetOrCreateUserRequest(classifier, logicalUserId,
                POSTGRES_TYPE, null, "test", Role.ADMIN.getRoleValue());
        log.info("Creating user");
        GetOrCreateUserResponse user = helperV3.getOrCreateUser(USER_API_URL_V3, getOrCreateUserRequest, 201);

        log.info("Check connections to user");
        helperV3.checkConnectionPostgresUser(user, "test", "test");

        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), DbaasHelperV3.TEST_NAMESPACE);

        log.info("Check that user is deleted too");
        log.info("Try to rotate user password");
        UserOperationRequest userOperationRequest = new UserOperationRequest();
        userOperationRequest.setUserId(user.getUserId());
        helperV3.rotateUserPasswordWithException(USER_API_URL_V3 + "/rotate-password",
                userOperationRequest,
                404);

        log.info("Try to check connections to user");
        assertThrows(CannotConnect.class, () -> helperV3.checkConnectionPostgresUser(user, "test", "test"));

        log.info("Try to delete user");
        helperV3.deleteUser(USER_API_URL_V3, userOperationRequest, 204);
    }

    @Test
    void UsersRestoreTest() throws IOException, SQLException {
        DatabaseCreateRequestV3 createDbRequest = getSimplePostgresCreateRequest("dbaas_auto_test_1");
        log.info("Create database");
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("dbaas_auto_test_1"), 201);
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(created_1);

        Map<String, Object> classifier = createDbRequest.getClassifier();
        RestoreUsersRequest restoreUsersRequest = new RestoreUsersRequest(classifier, POSTGRES_TYPE);
        log.info("Restore users");
        restoreUsers(USER_API_URL_V3 + "/restore", restoreUsersRequest, 200);
        log.info("Check connections to user");
        helperV3.checkConnectionPostgres(created_1);
    }


    private void testGetNotExistingDB(String type) throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), DbaasHelperV3.TEST_NAMESPACE);
        Map<String, Object> classifierMap = new HashMap<>();
        classifierMap.put("dbClassifier", "default");
        classifierMap.put("scope", "service");
        classifierMap.put("microserviceName", "non-existing-service");
        classifierMap.put("namespace", DbaasHelperV3.TEST_NAMESPACE);

        log.info("Get not existing database by classifier");
        try (Response response = helperV3.getDatabaseByClassifierAsResponse(authorization, classifierMap, type, DbaasHelperV3.TEST_NAMESPACE)) {
            assertThat(response.code(), is(404));
        }
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), DbaasHelperV3.TEST_NAMESPACE);
    }

    @NotNull
    private BaseMatcher<DatabaseResponse> inNamespace(final String namespace) {
        return new BaseMatcher<>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("should have namespace " + namespace);
            }

            @Override
            public boolean matches(Object o) {
                return ((DatabaseResponse) o).getNamespace().equals(namespace);
            }
        };
    }

    public SuccessfulRestoreUsersResponse restoreUsers(String api, RestoreUsersRequest request, int expected) {
        Request restoreUsers = helperV3.createRequest(api, helperV3.getClusterDbaAuthorization(), request, "POST");
        log.info("request: {}", restoreUsers);
        return helperV3.executeRequest(restoreUsers, SuccessfulRestoreUsersResponse.class, expected);
    }

}
