package com.netcracker.it.dbaas.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.netcracker.cloud.core.error.rest.tmf.TmfErrorResponse;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.response.MigrationResult;
import com.netcracker.it.dbaas.exceptions.CannotConnect;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.function.Consumer;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class DatabaseMigrationIT extends AbstractIT {

    private final static String MIGRATE_WITH_USER_CREATION_API = "api/v3/dbaas/migration/databases/with-user-creation";

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
        String errorPrefix = "registered database must contain ";
        RegisterDatabaseWithUserCreationRequest requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setType(null);
        sendAndCheckError(requestBody, errorPrefix + "dbType");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setClassifier(null);
        sendAndCheckError(requestBody, errorPrefix + "classifier");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setName(null);
        sendAndCheckError(requestBody, errorPrefix + "database name");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.setPhysicalDatabaseId(null);
        sendAndCheckError(requestBody, errorPrefix + "physical database id or dbHost");

        requestBody = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBody.getClassifier().remove("namespace");
        sendAndCheckError(requestBody, errorPrefix + "namespace in classifier");

    }

    @Test
    public void migrateWithUserCreation_NotValidClassifier() throws IOException {
        RegisterDatabaseWithUserCreationRequest requestBodySec = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBodySec.setClassifier(new TreeMap<>(Map.of("namespace", DbaasHelperV3.TEST_NAMESPACE)));
        String expectErrorMsg = "Invalid classifier. It does not match v3 format. Classifier: ";
        sendAndCheckError(requestBodySec, expectErrorMsg + requestBodySec.getClassifier());

        requestBodySec = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBodySec.getClassifier().remove("microserviceName");
        sendAndCheckError(requestBodySec, expectErrorMsg + requestBodySec.getClassifier());

        requestBodySec = getNotValidRegisterDatabaseWithUserCreationRequest();
        requestBodySec.getClassifier().remove("scope");
        sendAndCheckError(requestBodySec, expectErrorMsg + requestBodySec.getClassifier());
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
        Request request = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), "PUT");

        Map<String, MigrationResult> resultMap = sendRequest(request, HttpStatus.INTERNAL_SERVER_ERROR.value());

        Assertions.assertEquals("fake-name failed due to: Physical database fake-phys-id is not registered", resultMap.get(POSTGRES_TYPE).getFailureReasons().getFirst());
    }

    @Test
    public void migrateWithUserCreation_DatabaseNotExist() throws IOException {
        String physId = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200).
                getIdentified()
                .entrySet()
                .stream().filter(phys -> phys.getValue().isGlobal()).map(Map.Entry::getKey).findFirst().get();
        RegisterDatabaseWithUserCreationRequest requestBodySec = new RegisterDatabaseWithUserCreationRequest(
                new ClassifierBuilder().test("migrateWithUserCreation_NotValidPhysicalId").build(),
                "postgrsql",
                "fake-name",
                physId,
                null
        );
        Request request = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), "PUT");

        Map<String, MigrationResult> resultMap = sendRequest(request, HttpStatus.INTERNAL_SERVER_ERROR.value());

        Assertions.assertTrue(resultMap.get("postgrsql").getFailureReasons().getFirst().contains("fake-name failed due to: Could not find registered database by adapter"));
    }

    @Test
    @Tag("Smoke")
    public void migrateWithUserCreation_SuccessfullyMigratedPostgresql() throws IOException {
        checkDatabaseWasSuccessfullyMigrated(POSTGRES_TYPE, this::removePgMetadata, false);
    }

    @Test
    public void migrateWithUserCreation_SuccessfullyMigratedMongodb() throws IOException {
        Assumptions.assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongodb adapter. Skip test.");
        checkDatabaseWasSuccessfullyMigrated(MONGODB_TYPE, this::removeMongoMetadata, false);
    }

    @Test
    public void migrateWithUserCreation_SuccessfullyMigratedPostgresql_SetOnlyHostDb() throws IOException {
        checkDatabaseWasSuccessfullyMigrated(POSTGRES_TYPE, this::removePgMetadata, true);
    }

    @Test
    void migrateWithUserCreation_MigrateExternalAsInternal() throws IOException {
        Consumer<Map> removeMetadata = this::removePgMetadata;
        PhysicalDatabaseRegistrationResponseDTOV3 physDb = null;
        String physicalId = null;
        for (Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physicalDbEntrySet : helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200).
                getIdentified().entrySet()) {
            if (physicalDbEntrySet.getValue().isGlobal()) {
                physDb = physicalDbEntrySet.getValue();
                physicalId = physicalDbEntrySet.getKey();
            }
        }
        String adapterAddress = physDb.getAdapterAddress();
        URL portForward = helperV3.createPortForward(adapterAddress);
        String apiVersion = physDb.getSupportedVersion();

        // create database via adapter so it can be registered as external
        Call call = okHttpClient.newCall((new Request.Builder()
                .url(portForward.toString() + "api/" + apiVersion + "/dbaas/adapter/postgresql/databases")
                .post(RequestBody.create("{\"metadata\":{\"classifier\":{\"microserviceName\":\"dbaas-test-service\",\"scope\":\"service\",\"dbaas_auto_test\":\"ext-as-int\",\"namespace\":\"" + DbaasHelperV3.TEST_NAMESPACE + "\"}}}", JSON))
                .addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(("dbaas-aggregator:dbaas-aggregator").getBytes()))
                .addHeader("X-Request-Id", getRequestId())
                .build()));
        Map map;
        try (Response response = call.execute()) {
            String resBody = response.body().string();
            log.info("Adapter {} created database: {}", adapterAddress, resBody);
            assumeTrue(response.code() == 201, "dbaas-adapter could not create database, skip test");
            map = helperV3.objectMapper.readValue(resBody, Map.class);
        } catch (IOException e) {
            log.debug("Error while creating database {}", adapterAddress, e);
            return;
        }
        removeMetadata.accept(map);

        // register created database as external
        String dbName = map.get("name").toString();
        List<Map<String, Object>> listOfConnProps = (List<Map<String, Object>>) map.get("connectionProperties");
        ImmutableMap<String, Object> classifier = ImmutableMap.of("microserviceName", TEST_MICROSERVICE_NAME, "scope", "service", TEST_CLASSIFIER_KEY, "ext-as-int", "namespace", DbaasHelperV3.TEST_NAMESPACE);
        ExternalDatabaseRequestV3 externalRegistrationRequest = ExternalDatabaseRequestV3.builder()
                .classifier(classifier)
                .connectionProperties(listOfConnProps)
                .dbName(dbName)
                .originService(TEST_MICROSERVICE_NAME)
                .userRole(Role.ADMIN.getRoleValue())
                .type(POSTGRES_TYPE).build();
        Request request = helperV3.createRequest(String.format(EXTERNALLY_MANAGEABLE_V3, TEST_NAMESPACE), helperV3.getClusterDbaAuthorization(), externalRegistrationRequest, "PUT");
        ExternalDatabaseResponseV3 externalDatabaseResponse = helperV3.executeRequest(request, ExternalDatabaseResponseV3.class, 201);

        // re-register external database as internal via 'with-user-creation'
        RegisterDatabaseWithUserCreationRequest requestBodySec = new RegisterDatabaseWithUserCreationRequest(
                classifier,
                POSTGRES_TYPE,
                dbName,
                physicalId,
                null
        );
        Request migraterequest = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), "PUT");
        Map<String, MigrationResult> resultMap = sendRequest(migraterequest, HttpStatus.OK.value());
        MigrationResult migrationResult = resultMap.get(POSTGRES_TYPE);
        Assertions.assertNotNull(migrationResult);
        Assertions.assertEquals(dbName, migrationResult.getMigrated().getFirst());
        DatabaseV3 migratedDb = migrationResult.getMigratedDbInfo().getFirst();
        Assertions.assertNotNull(migratedDb);
        Assertions.assertEquals(dbName, migratedDb.getName());

        // get database by classifier and check that it is not external anymore
        DatabaseResponse databaseByClassifierAsPOJO = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), externalDatabaseResponse.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertFalse(databaseByClassifierAsPOJO.isExternallyManageable());

        // call get or create database
        List<DatabaseResponse> databases = helperV3.createDatabases(helperV3.getClusterDbaAuthorization(), "ext-as-int", 200, TEST_NAMESPACE, POSTGRES_TYPE);
        assertEquals(1, databases.size());
        DatabaseResponse databaseByGetOrCreate = databases.getFirst();
        assertFalse(databaseByGetOrCreate.isExternallyManageable());
    }

    private void checkDatabaseWasSuccessfullyMigrated(String dbType, Consumer<Map> removeMetadata, boolean withHostDb) throws IOException {
        PhysicalDatabaseRegistrationResponseDTOV3 physDb = null;
        String physicalId = null;
        for (Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3> physicalDbEntrySet : helperV3.getRegisteredPhysicalDatabases(dbType, helperV3.getClusterDbaAuthorization(), 200).
                getIdentified().entrySet()) {
            if (physicalDbEntrySet.getValue().isGlobal()) {
                physDb = physicalDbEntrySet.getValue();
                physicalId = physicalDbEntrySet.getKey();
            }
        }
        String adapterAddress = physDb.getAdapterAddress();
        URL portForward = helperV3.createPortForward(adapterAddress);
        String apiVersion = physDb.getSupportedVersion();
        Call call = okHttpClient.newCall((new Request.Builder()
                .url(portForward.toString() + "api/" + apiVersion + "/dbaas/adapter/" + dbType + "/databases")
                .post(RequestBody.create("{\"metadata\":{\"classifier\":{\"microserviceName\":\"tenant-manager\",\"scope\":\"service\"}}}", JSON))
                .addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(("dbaas-aggregator:dbaas-aggregator").getBytes()))
                .addHeader("X-Request-Id", getRequestId())
                .build()));
        Map map;
        try (Response response = call.execute()) {
            String resBody = response.body().string();
            log.info("Adapter {} created database: {}", adapterAddress, resBody);
            assumeTrue(response.code() == 201, "dbaas-adapter could not create database, skip test");
            map = helperV3.objectMapper.readValue(resBody, Map.class);
        } catch (IOException e) {
            log.debug("Error while creating database {}", adapterAddress, e);
            return;
        }
        removeMetadata.accept(map);

        String hostDbRequest = null;
        String physicalDbIdRequest = physicalId;
        if (withHostDb) {
            hostDbRequest = dbType + "." + physicalId;
            physicalDbIdRequest = null;
        }
        Map<String, Object> classifier = new ClassifierBuilder().test("migrateWithUserCreation_NotValidPhysicalId").build();
        RegisterDatabaseWithUserCreationRequest requestBodySec = new RegisterDatabaseWithUserCreationRequest(
                classifier,
                dbType,
                map.get("name").toString(),
                physicalDbIdRequest,
                hostDbRequest
        );
        Request request = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBodySec), "PUT");

        Map<String, MigrationResult> resultMap = sendRequest(request, HttpStatus.OK.value());


        MigrationResult migrationResult = resultMap.get(dbType);
        Assertions.assertNotNull(migrationResult);
        Assertions.assertEquals(map.get("name").toString(), migrationResult.getMigrated().getFirst());
        DatabaseV3 migratedDb = migrationResult.getMigratedDbInfo().getFirst();
        Assertions.assertNotNull(migratedDb);
        Assertions.assertEquals(map.get("name").toString(), migratedDb.getName());
        Assertions.assertEquals(classifier, migratedDb.getClassifier());
        Assertions.assertEquals(physicalId, migratedDb.getPhysicalDatabaseId());
        Assertions.assertEquals(dbType, migratedDb.getType());
        Assertions.assertEquals(classifier.get("namespace"), migratedDb.getNamespace());
        Map<String, Object> connectionProperty = migratedDb.getConnectionPropertyByRole(Role.ADMIN);
        Assertions.assertTrue(connectionProperty != null && !connectionProperty.isEmpty());
        Assertions.assertEquals(physDb.getSupportedRoles().size(), migratedDb.getConnectionProperties().size());
        Assertions.assertTrue(connectionProperty.get("username") != null && !connectionProperty.get("username").toString().isEmpty());
        Assertions.assertTrue(connectionProperty.get("password") != null && !connectionProperty.get("password").toString().isEmpty());
        Assertions.assertTrue(connectionProperty.get("url") != null && !connectionProperty.get("url").toString().isEmpty());
    }

    @SneakyThrows
    private void removePgMetadata(Map map) {
        log.info("remove pg metadata");
        List<Map<String, Object>> connectionProperties = (List<Map<String, Object>>) map.get("connectionProperties");
        Map<String, Object> adminConnectionProperty = connectionProperties.stream().filter(cp -> cp.get("role").equals(Role.ADMIN.getRoleValue())).findFirst().get();
        log.info("connection property {}", adminConnectionProperty);
        DatabaseResponse databaseResponse = DatabaseResponse.builder().connectionProperties(adminConnectionProperty).build();
        try (Connection connection = helperV3.connectPg(databaseResponse);
             Statement statement = connection.createStatement()) {
            try {
                String sql = "DROP TABLE _dbaas_metadata";
                statement.executeUpdate(sql);
            } catch (Throwable ex) {
                throw new CannotConnect(ex);
            }
        }
    }

    @SneakyThrows
    private void removeMongoMetadata(Map map) {
        log.info("remove mongodb metadata");
        List<Map<String, Object>> connectionProperties = (List<Map<String, Object>>) map.get("connectionProperties");
        Map<String, Object> adminConnectionProperty = connectionProperties.stream().filter(cp -> cp.get("role").equals(Role.ADMIN.getRoleValue())).findFirst().get();
        log.info("connection property {}", adminConnectionProperty);
        DatabaseResponse databaseResponse = DatabaseResponse.builder().connectionProperties(adminConnectionProperty).build();
        try (MongoClient mongoClient = helperV3.connectMongo(databaseResponse, false)) {
            MongoDatabase mongoDatabase = mongoClient.getDatabase((String) adminConnectionProperty.get("authDbName"));
            mongoDatabase.getCollection("autotestsData").insertOne(new Document("testkey", "testvalue")); // if database does not have any collections mongo drops such db, so we create fake collection
            mongoDatabase.getCollection("_dbaas_metadata").drop();
            assertFalse(mongoDatabase.listCollectionNames()
                    .into(new ArrayList<>()).contains("_dbaas_metadata"));
        }
    }


    private Map<String, MigrationResult> sendRequest(Request request, int expectedCode) {
        Map response = helperV3.executeRequest(request, Map.class, expectedCode);
        return helperV3.objectMapper.convertValue(response, new TypeReference<>() {
        });
    }

    private void sendAndCheckError(RegisterDatabaseWithUserCreationRequest requestBody, String expectErrorMsg) {
        Request request = helperV3.createRequest(MIGRATE_WITH_USER_CREATION_API, helperV3.getClusterDbaAuthorization(), Collections.singletonList(requestBody), "PUT");
        TmfErrorResponse response = helperV3.executeRequest(request, TmfErrorResponse.class, HttpStatus.BAD_REQUEST.value());
        Assertions.assertEquals(expectErrorMsg, response.getDetail());
    }

    private RegisterDatabaseWithUserCreationRequest getNotValidRegisterDatabaseWithUserCreationRequest() {
        Map<String, Object> classifier = new ClassifierBuilder().test("migrateWithUserCreation_NotValidRequest").build();
        return new RegisterDatabaseWithUserCreationRequest(
                new TreeMap<>(classifier),
                "postgresql",
                "migrated_db",
                "physical_db_id",
                null
        );
    }
}
