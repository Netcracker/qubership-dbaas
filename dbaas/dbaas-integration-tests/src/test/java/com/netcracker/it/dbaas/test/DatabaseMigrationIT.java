package com.netcracker.it.dbaas.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.netcracker.cloud.core.error.rest.tmf.TmfErrorResponse;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.response.MigrationResult;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import com.netcracker.it.dbaas.helpers.MigrationHelper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static com.netcracker.it.dbaas.helpers.MigrationHelper.CONNECTION_PROPERTIES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Slf4j
public class DatabaseMigrationIT extends AbstractIT {

    private static final String BASE_MIGRATE_API = "api/v3/dbaas/migration/databases";
    private static final String MIGRATE_WITH_USER_CREATION_API = BASE_MIGRATE_API + "/with-user-creation";

    private static final String DATABASE_ERROR_MSG = "registered database must contain ";
    private static final String INVALID_CLASSIFIER_ERROR_MSG = "Invalid classifier. It does not match v3 format. Classifier: ";
    private static final String MIGRATE_EXTERNAL_TEST_VAL = "migrate_external_";
    private static final String USERNAME = "username";
    private static final String NAME = "name";
    private static final String PASSWORD = "password";
    private static final String URL = "url";
    private static final String INCORRECT_POSTGRES_TYPE = "postgrsql";
    private static final String TEST_MIGRATED_DB_NAME = "migrated_db";
    private static final String TEST_PHYSICAL_DB_ID = "physical_db_id";

    private static MigrationHelper migrationHelper;

    @BeforeAll
    static void initHelper() {
        migrationHelper = new MigrationHelper(helperV3);
    }

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");

        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), DbaasHelperV3.TEST_NAMESPACE);
    }

    @Test
    public void migrateWithUserCreation_NotValidRequest() throws IOException {
        RegisterDatabaseWithUserCreationRequest requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setType(null);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBody, DATABASE_ERROR_MSG + "dbType");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setClassifier(null);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBody, DATABASE_ERROR_MSG + "classifier");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setName(null);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBody, DATABASE_ERROR_MSG + "database name");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setPhysicalDatabaseId(null);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBody, DATABASE_ERROR_MSG + "physical database id or dbHost");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.getClassifier().remove(NAMESPACE);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBody, DATABASE_ERROR_MSG + "namespace in classifier");
    }

    @Test
    public void migrateWithUserCreation_NotValidClassifier() throws IOException {
        RegisterDatabaseWithUserCreationRequest requestBodySec = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBodySec.setClassifier(new TreeMap<>(Map.of(NAMESPACE, DbaasHelperV3.TEST_NAMESPACE)));

        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBodySec, INVALID_CLASSIFIER_ERROR_MSG + requestBodySec.getClassifier());

        requestBodySec = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBodySec.getClassifier().remove(MICROSERVICE_NAME);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBodySec, INVALID_CLASSIFIER_ERROR_MSG + requestBodySec.getClassifier());

        requestBodySec = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBodySec.getClassifier().remove("scope");
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBodySec, INVALID_CLASSIFIER_ERROR_MSG + requestBodySec.getClassifier());
    }

    @Test
    public void migrateWithUserCreation_NotValidPhysicalId() {
        RegisterDatabaseWithUserCreationRequest requestBodySec = new RegisterDatabaseWithUserCreationRequest(
                new ClassifierBuilder().test("migrateWithUserCreation_NotValidPhysicalId").build(),
                POSTGRES_TYPE,
                "fake-name",
                "fake-phys-id",
                null
        );
        Request request = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), HttpMethod.PUT.name());

        Map<String, MigrationResult> resultMap = sendRequest(request, HttpStatus.INTERNAL_SERVER_ERROR.value());

        Assertions.assertEquals("fake-name failed due to: Physical database fake-phys-id is not registered", resultMap.get(POSTGRES_TYPE).getFailureReasons().getFirst());
    }

    @Test
    public void migrateWithUserCreation_DatabaseNotExist() throws IOException {
        String physId = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), HttpStatus.OK.value()).
                getIdentified()
                .entrySet()
                .stream().filter(phys -> phys.getValue().isGlobal()).map(Map.Entry::getKey).findFirst().get();
        RegisterDatabaseWithUserCreationRequest requestBodySec = new RegisterDatabaseWithUserCreationRequest(
                new ClassifierBuilder().test("migrateWithUserCreation_NotValidPhysicalId").build(),
                INCORRECT_POSTGRES_TYPE,
                "fake-name",
                physId,
                null
        );
        Request request = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), HttpMethod.PUT.name());

        Map<String, MigrationResult> resultMap = sendRequest(request, HttpStatus.INTERNAL_SERVER_ERROR.value());

        Assertions.assertTrue(resultMap.get(INCORRECT_POSTGRES_TYPE).getFailureReasons().getFirst().contains("fake-name failed due to: Could not find registered database by adapter"));
    }

    @Test
    @Tag("Smoke")
    public void migrateWithUserCreation_SuccessfullyMigratedPostgresql() throws IOException, SQLException {
        testDatabaseMigration(POSTGRES_TYPE, migrationHelper::removePgMetadata, false, true);
    }

    @Test
    public void migrateWithUserCreation_SuccessfullyMigratedMongodb() throws IOException, SQLException {
        Assumptions.assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongodb adapter. Skip test.");
        testDatabaseMigration(MONGODB_TYPE, migrationHelper::removeMongoMetadata, false, true);
    }

    @Test
    public void migrateWithUserCreation_SuccessfullyMigratedPostgresql_SetOnlyHostDb() throws IOException, SQLException {
        testDatabaseMigration(POSTGRES_TYPE, migrationHelper::removePgMetadata, true, true);
    }

    @Test
    void migrateWithUserCreation_MigrateExternalAsInternal() throws IOException, SQLException {
        Consumer<Map> removeMetadata = migrationHelper::removePgMetadata;
        String physicalId = null;
        for (Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physicalDbEntrySet : helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200).
                getIdentified().entrySet()) {
            if (physicalDbEntrySet.getValue().isGlobal()) {
                physicalId = physicalDbEntrySet.getKey();
            }
        }

        // create database via adapter so it can be registered as external
        Map map = migrationHelper.createDbViaAdapter(POSTGRES_TYPE, TEST_MICROSERVICE_NAME, DbaasHelperV3.TEST_NAMESPACE);
        removeMetadata.accept(map);

        // register created database as external
        String dbName = map.get(NAME).toString();
        List<Map<String, Object>> listOfConnProps = (List<Map<String, Object>>) map.get(CONNECTION_PROPERTIES);
        Map<String, Object> classifier = Map.of(MICROSERVICE_NAME, TEST_MICROSERVICE_NAME, "scope", "service", TEST_CLASSIFIER_KEY, "ext-as-int", NAMESPACE, DbaasHelperV3.TEST_NAMESPACE);
        ExternalDatabaseRequestV3 externalRegistrationRequest = ExternalDatabaseRequestV3.builder()
                .classifier(classifier)
                .connectionProperties(listOfConnProps)
                .dbName(dbName)
                .originService(TEST_MICROSERVICE_NAME)
                .userRole(Role.ADMIN.getRoleValue())
                .type(POSTGRES_TYPE).build();
        Request request = helperV3.createRequest(String.format(EXTERNALLY_MANAGEABLE_V3, TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), externalRegistrationRequest, HttpMethod.PUT.name());
        ExternalDatabaseResponseV3 externalDatabaseResponse = helperV3.executeRequest(request, ExternalDatabaseResponseV3.class, 201);

        // re-register external database as internal via 'with-user-creation'
        RegisterDatabaseWithUserCreationRequest requestBodySec = new RegisterDatabaseWithUserCreationRequest(
                classifier,
                POSTGRES_TYPE,
                dbName,
                physicalId,
                null
        );
        Request migraterequest = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), HttpMethod.PUT.name());
        Map<String, MigrationResult> resultMap = sendRequest(migraterequest, HttpStatus.OK.value());
        MigrationResult migrationResult = resultMap.get(POSTGRES_TYPE);
        Assertions.assertNotNull(migrationResult);
        Assertions.assertEquals(dbName, migrationResult.getMigrated().getFirst());
        DatabaseV3 migratedDb = migrationResult.getMigratedDbInfo().getFirst();
        Assertions.assertNotNull(migratedDb);
        Assertions.assertEquals(dbName, migratedDb.getName());

        // get database by classifier and check that it is not external anymore
        DatabaseResponse databaseByClassifierAsPOJO = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), externalDatabaseResponse.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, HttpStatus.OK.value());
        assertFalse(databaseByClassifierAsPOJO.isExternallyManageable());

        // call get or create database
        List<DatabaseResponse> databases = helperV3.createDatabases(helperV3.getClusterDbaAuthorization(), "ext-as-int", HttpStatus.OK.value(), TEST_NAMESPACE, POSTGRES_TYPE);
        assertEquals(1, databases.size());
        DatabaseResponse databaseByGetOrCreate = databases.getFirst();
        assertFalse(databaseByGetOrCreate.isExternallyManageable());

        helperV3.checkConnectionPostgres(databaseByGetOrCreate);

        helperV3.deleteDatabasesByClassifierRequest(TEST_NAMESPACE, POSTGRES_TYPE, createClassifierWithRolesRequest(classifier), HttpStatus.OK.value());

        Assertions.assertThrows(CannotConnect.class, () -> {
            helperV3.checkConnectionPostgres(databaseByGetOrCreate);
        }, "Connection should fail after database deletion");
    }

    @Test
    public void migrateDatabase_NotValidClassifier() {
        RegisterDatabaseRequest requestBodySec = getNotValidRegisterDatabaseRequest();
        requestBodySec.setClassifier(new TreeMap<>(Map.of(NAMESPACE, DbaasHelperV3.TEST_NAMESPACE)));

        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBodySec, INVALID_CLASSIFIER_ERROR_MSG + requestBodySec.getClassifier());

        requestBodySec = getNotValidRegisterDatabaseRequest();
        requestBodySec.getClassifier().remove(MICROSERVICE_NAME);
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBodySec, INVALID_CLASSIFIER_ERROR_MSG + requestBodySec.getClassifier());

        requestBodySec = getNotValidRegisterDatabaseRequest();
        requestBodySec.getClassifier().remove("scope");
        sendAndCheckBadRequestError(MIGRATE_WITH_USER_CREATION_API, requestBodySec, INVALID_CLASSIFIER_ERROR_MSG + requestBodySec.getClassifier());
    }

    @Test
    public void migrateDatabaseSuccessfullyMigratedPostgreSql() throws IOException, SQLException {
        testDatabaseMigration(POSTGRES_TYPE, migrationHelper::removePgMetadata, false, false);
    }

    @Test
    public void migrateDatabaseSuccessfullyMigratedMongodb() throws IOException, SQLException {
        Assumptions.assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongodb adapter. Skip test.");
        testDatabaseMigration(MONGODB_TYPE, migrationHelper::removeMongoMetadata, false, false);
    }

    @Test
    public void migrateDatabaseSuccessfullyMigratedPostgresql_SetOnlyHostDb() throws IOException, SQLException {
        testDatabaseMigration(POSTGRES_TYPE, migrationHelper::removePgMetadata, true, false);
    }

    private ClassifierWithRolesRequest createClassifierWithRolesRequest(Map<String, Object> classifier) {
        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
        classifierWithRolesRequest.setOriginService((String) classifier.get(MICROSERVICE_NAME));
        return classifierWithRolesRequest;
    }

    private void testDatabaseMigration(String dbType, Consumer<Map> removeMetadata, boolean withHostDb,
                                       boolean withUserCreation) throws IOException, SQLException {
        Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physDbEntry = migrationHelper.getGlobalPhysicalDbEntry(dbType);
        String physicalId = physDbEntry.getKey();
        PhysicalDatabaseRegistrationResponseDTOV3 physDb = physDbEntry.getValue();

        Map adapterResponse = migrationHelper.createDbViaAdapter(dbType, TEST_MICROSERVICE_NAME, DbaasHelperV3.TEST_NAMESPACE);
        removeMetadata.accept(adapterResponse);
        String dbName = adapterResponse.get(NAME).toString();

        String hostDbRequest = helperV3.getServiceNameFromUrl(physDb.getAdapterAddress()) + "."
                + helperV3.getServiceNamespaceFromUrl(physDb.getAdapterAddress());

        String physicalDbIdRequest = withHostDb ? null : physicalId;

        Map<String, Object> classifier = new ClassifierBuilder()
                .test(MIGRATE_EXTERNAL_TEST_VAL + dbType)
                .build();

        Object requestBody;
        String apiEndpoint;

        if (withUserCreation) {
            apiEndpoint = MIGRATE_WITH_USER_CREATION_API;
            requestBody = new RegisterDatabaseWithUserCreationRequest(
                    classifier,
                    dbType,
                    dbName,
                    physicalDbIdRequest,
                    hostDbRequest);
        } else {
            apiEndpoint = BASE_MIGRATE_API;
            List<ConnectionProperties> connProps = helperV3.objectMapper.convertValue(
                    adapterResponse.get(CONNECTION_PROPERTIES), new TypeReference<>() {
                    });
            List<DbResource> resources = helperV3.objectMapper.convertValue(
                    adapterResponse.get("resources"), new TypeReference<>() {
                    });
            requestBody = new RegisterDatabaseRequest(
                    classifier,
                    connProps,
                    resources,
                    DbaasHelperV3.TEST_NAMESPACE,
                    physDb.getAdapterId(),
                    dbType,
                    dbName,
                    physicalDbIdRequest,
                    hostDbRequest);
        }

        Request request = helperV3.createRequest(apiEndpoint, helperV3.getClusterDbaAuthorization(),
                Collections.singletonList(requestBody), HttpMethod.PUT.name());

        Map<String, MigrationResult> resultMap = sendRequest(request, HttpStatus.OK.value());
        verifyMigrationResults(resultMap, adapterResponse, dbType, classifier, physicalId, physDb);

        DatabaseResponse databaseResponse = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(),
                classifier, DbaasHelperV3.TEST_NAMESPACE, dbType, HttpStatus.OK.value());

        helperV3.checkConnection(false, databaseResponse, null, null, false);

        helperV3.deleteDatabasesByClassifierRequest(TEST_NAMESPACE, dbType, createClassifierWithRolesRequest(classifier),
                HttpStatus.OK.value());

        helperV3.checkConnection(true, databaseResponse, null, null, false);
    }

    private Map<String, MigrationResult> sendRequest(Request request, int expectedCode) {
        Map response = helperV3.executeRequest(request, Map.class, expectedCode);
        return helperV3.objectMapper.convertValue(response, new TypeReference<>() {
        });
    }

    private <T> void sendAndCheckBadRequestError(String endpoint, T requestBody, String expectErrorMsg) {
        Request request = helperV3.createRequest(endpoint, helperV3.getClusterDbaAuthorization(),
                Collections.singletonList(requestBody), HttpMethod.PUT.name());
        TmfErrorResponse response = helperV3.executeRequest(request, TmfErrorResponse.class, HttpStatus.BAD_REQUEST.value());
        Assertions.assertEquals(expectErrorMsg, response.getDetail());
    }

    private RegisterDatabaseWithUserCreationRequest getNotValidRegisterDatabaseWithUserCreationRequest() {
        Map<String, Object> classifier = new ClassifierBuilder().test("migrateWithUserCreation_NotValidRequest").build();
        return new RegisterDatabaseWithUserCreationRequest(
                new TreeMap<>(classifier),
                POSTGRES_TYPE,
                TEST_MIGRATED_DB_NAME,
                TEST_PHYSICAL_DB_ID,
                null
        );
    }

    private void verifyMigrationResults(Map<String, MigrationResult> resultMap, Map adapterResponse, String dbType, Map<String, Object> classifier,
                                        String physicalId, PhysicalDatabaseRegistrationResponseDTOV3 physDb) {
        MigrationResult migrationResult = resultMap.get(dbType);
        Assertions.assertNotNull(migrationResult);
        Assertions.assertEquals(adapterResponse.get(NAME).toString(), migrationResult.getMigrated().getFirst());
        DatabaseV3 migratedDb = migrationResult.getMigratedDbInfo().getFirst();
        Assertions.assertNotNull(migratedDb);
        Assertions.assertEquals(adapterResponse.get(NAME).toString(), migratedDb.getName());
        Assertions.assertEquals(classifier, migratedDb.getClassifier());
        Assertions.assertEquals(physicalId, migratedDb.getPhysicalDatabaseId());
        Assertions.assertEquals(dbType, migratedDb.getType());
        Assertions.assertEquals(classifier.get(NAMESPACE), migratedDb.getNamespace());
        Map<String, Object> connectionProperty = migratedDb.getConnectionPropertyByRole(Role.ADMIN);
        Assertions.assertTrue(connectionProperty != null && !connectionProperty.isEmpty());
        Assertions.assertEquals(physDb.getSupportedRoles().size(), migratedDb.getConnectionProperties().size());
        Assertions.assertTrue(connectionProperty.get(USERNAME) != null && !connectionProperty.get(USERNAME).toString().isEmpty());
        Assertions.assertTrue(connectionProperty.get(PASSWORD) != null && !connectionProperty.get(PASSWORD).toString().isEmpty());
        Assertions.assertTrue(connectionProperty.get(URL) != null && !connectionProperty.get(URL).toString().isEmpty());
    }

    private RegisterDatabaseRequest getNotValidRegisterDatabaseRequest() {
        Map<String, Object> classifier = new ClassifierBuilder()
                .test("migrateDatabase_NotValidRequest")
                .build();
        List<ConnectionProperties> connProps = new ArrayList<>();
        List<DbResource> resources = new ArrayList<>();
        return new RegisterDatabaseRequest(
                new TreeMap<>(classifier),
                connProps,
                resources,
                DbaasHelperV3.TEST_NAMESPACE,
                null,
                POSTGRES_TYPE,
                TEST_MIGRATED_DB_NAME,
                TEST_PHYSICAL_DB_ID,
                null
        );
    }
}
