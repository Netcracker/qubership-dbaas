package com.netcracker.it.dbaas.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class DatabaseOperationV3IT extends AbstractIT {

    private final static String RECREATE_API_V3 = "api/v3/dbaas/namespaces/%s/databases/recreate";

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE + "-additional");
    }

    @Test
    public void passwordChangeOneDbPG() throws IOException {
        String authorization = helperV3.getClusterDbaAuthorization();
        log.info("Create postgres db");
        List<DatabaseResponse> databases = helperV3.createDatabases(authorization, "password-change-IT-1", 201, TEST_NAMESPACE, POSTGRES_TYPE);
        log.info("Create postgres db second pair");
        databases.addAll(helperV3.createDatabases(authorization, "password-change-IT-2", 201, TEST_NAMESPACE, POSTGRES_TYPE));
        assertThat(databases, hasSize(2));

        PasswordChangeResponse passwordChangeResponse = changePassword("password-change-IT-2", POSTGRES_TYPE, 200, TEST_NAMESPACE);
        log.info("Result of change password from postgres db {}", passwordChangeResponse);
        checkResultPasswordChangeOneDb(passwordChangeResponse, databases, "password-change-IT-2", POSTGRES_TYPE);
    }

    @Test
    public void passwordChangeOneDbMongo() throws IOException {
        Assumptions.assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");
        String authorization = helperV3.getClusterDbaAuthorization();
        log.info("Create mongo db");
        List<DatabaseResponse> databases = helperV3.createDatabases(authorization, "password-change-IT-1", 201, TEST_NAMESPACE, MONGODB_TYPE);
        log.info("Create mongo db");
        databases.addAll(helperV3.createDatabases(authorization, "password-change-IT-2", 201, TEST_NAMESPACE, MONGODB_TYPE));
        assertThat(databases, hasSize(2));

        PasswordChangeResponse passwordChangeResponse = changePassword("password-change-IT-1", MONGODB_TYPE, 200, TEST_NAMESPACE);
        log.info("Result of change password from mongo db {}", passwordChangeResponse);
        checkResultPasswordChangeOneDb(passwordChangeResponse, databases, "password-change-IT-1", MONGODB_TYPE);
    }

    @Test
    public void passwordChangeSeveralDb() throws IOException, InterruptedException {
        String authorization = helperV3.getClusterDbaAuthorization();
        List<DatabaseResponse> databases = new ArrayList<>();
        log.info("Create first postgres db");
        databases.add(helperV3.createDatabase(authorization, "password-change-IT-1", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false));
        Thread.sleep(5000); // for ci stable
        log.info("Create second postgres db");
        databases.add(helperV3.createDatabase(authorization, "password-change-IT-2", 201, POSTGRES_TYPE, null, TEST_NAMESPACE, false));
        Thread.sleep(5000); // for ci stable
        log.info("Create third postgres db");
        databases.add(helperV3.createDatabase(authorization, "password-change-IT-2", 201, POSTGRES_TYPE, null, TEST_NAMESPACE + "-additional", false));
        assertThat(databases, hasSize(3));
        int rolesCount = helperV3.getDatabasesByNamespace(TEST_NAMESPACE).getFirst().getConnectionProperties().size();
        List<DatabaseV3> databasesV3 = new ArrayList<>();
        databasesV3.addAll(helperV3.getDatabasesByNamespace(TEST_NAMESPACE));
        databasesV3.addAll(helperV3.getDatabasesByNamespace(TEST_NAMESPACE + "-additional"));
        databases.forEach(d -> assertTrue(databasesV3.stream().anyMatch(d3 -> d3.getName().equals(d.getName()))));
        List<DatabaseResponse> databasesWithAllConnectionProperties = new ArrayList<>();
        for (DatabaseV3 db : databasesV3) {
            for (Map<String, Object> cp : db.getConnectionProperties())
                databasesWithAllConnectionProperties.add(helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), db.getClassifier(), db.getNamespace(), db.getType(), 200, (String) cp.get("role")));
        }
        PasswordChangeResponse passwordChangeResponse = changePassword(null, POSTGRES_TYPE, 200, TEST_NAMESPACE);
        checkResultPasswordSeveralDb(passwordChangeResponse, "password-change-IT-1", "password-change-IT-2", TEST_NAMESPACE, databasesWithAllConnectionProperties, POSTGRES_TYPE);
    }

    @Test
    public void updateClassifier() throws IOException {
        Map<String, Object> classifier = new ClassifierBuilder().build();
        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 201);
        DatabaseResponse foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), database.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertEquals(database.getName(), foundedDb.getName());
        Map<String, Object> targetClassifier = new HashMap<>();
        targetClassifier.put("tenantId", "123456789");
        targetClassifier.put("microserviceName", "service-name");
        targetClassifier.put("scope", "tenant");
        targetClassifier.put("namespace", TEST_NAMESPACE);
        UpdateClassifierRequestV3 requestBody = new UpdateClassifierRequestV3();
        requestBody.setFrom(classifier);
        requestBody.setTo(targetClassifier);
        Request request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/update-classifier/%s", TEST_NAMESPACE, POSTGRES_TYPE),
                helperV3.getDbaasDbEditorAuthorization(),
                requestBody,
                "PUT");

        DatabaseV3 updatedDatabase = helperV3.executeRequest(request, DatabaseV3.class, 200);

        Map<String, Object> expectClassifier = new HashMap<>(targetClassifier);
        assertEquals(expectClassifier, updatedDatabase.getClassifier());
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), database.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 404);
        foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), targetClassifier, TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertEquals(expectClassifier, foundedDb.getClassifier());
        assertEquals(updatedDatabase.getName(), foundedDb.getName());
    }

    @Test
    void testUpdateConnectionProperties() throws IOException {
        Map<String, Object> classifier = new ClassifierBuilder().build();
        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 201);
        DatabaseResponse foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), database.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertEquals(database.getName(), foundedDb.getName());

        String testKey = "testKey";
        String testValue = "testValue";
        Map<String, Object> connectionProperties = database.getConnectionProperties();
        connectionProperties.put(testKey, testValue);
        assertTrue(connectionProperties.containsKey("role"));

        UpdateConnectionPropertiesRequest requestBody = new UpdateConnectionPropertiesRequest();
        requestBody.setClassifier(classifier);
        requestBody.setConnectionProperties(connectionProperties);

        Request request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/update-connection/%s", TEST_NAMESPACE, POSTGRES_TYPE),
                helperV3.getDbaasDbEditorAuthorization(),
                requestBody,
                "PUT");
        DatabaseResponse updatedDatabase = helperV3.executeRequest(request, DatabaseResponse.class, 200);
        assertEquals(connectionProperties, updatedDatabase.getConnectionProperties());

        foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertEquals(updatedDatabase.getName(), foundedDb.getName());
        assertEquals(connectionProperties, foundedDb.getConnectionProperties());
    }

    @Test
    void testUpdateConnectionPropertiesWithPhysicalDatabaseId() throws IOException {
        Map<String, Object> classifier = new ClassifierBuilder().build();
        log.info("Start to create database with classifier = {}", classifier);
        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 201);

        log.info("Getting database by classifier = {}", classifier);
        DatabaseResponse foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), database.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertEquals(database.getName(), foundedDb.getName());

        String testKey = "testKey";
        String testValue = "testValue";
        String oldPhysicalDatabaseId = foundedDb.getPhysicalDatabaseId();
        String oldName = foundedDb.getName();
        String updatedName = "new-name";
        log.info("Getting all databases in namespace = {}", foundedDb.getNamespace());

        List<DatabaseV3> databases = getDatabasesByNamespaceWithResorces(TEST_NAMESPACE);
        DatabaseResponse finalFoundedDb = foundedDb;
        DatabaseV3 databaseWithResources = databases.stream().filter(db -> (db.getName().equals(finalFoundedDb.getName()))).findFirst().get();
        List<DbResource> updatedResources = new ArrayList<>(databaseWithResources.getResources());
        updatedResources.stream().filter(dbResource -> dbResource.getName().equals(finalFoundedDb.getName()))
                .forEach(dbResource -> dbResource.setName(updatedName));

        Map<String, Object> oldConnectionProperties = database.getConnectionProperties();
        Map<String, Object> updatedConnectionProperties = new HashMap<>(oldConnectionProperties);
        updatedConnectionProperties.put(testKey, testValue);
        assertTrue(updatedConnectionProperties.containsKey("role"));

        UpdateConnectionPropertiesRequest requestBody = new UpdateConnectionPropertiesRequest();
        requestBody.setClassifier(classifier);
        requestBody.setConnectionProperties(updatedConnectionProperties);
        requestBody.setPhysicalDatabaseId(oldPhysicalDatabaseId);
        requestBody.setDbName(updatedName);
        requestBody.setResources(updatedResources);

        Request request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/update-connection/%s", TEST_NAMESPACE, POSTGRES_TYPE),
                helperV3.getDbaasDbEditorAuthorization(),
                requestBody,
                "PUT");
        log.info("Send request to update connection properties and physical database id, request = {}", requestBody);
        DatabaseResponse updatedDatabase = helperV3.executeRequest(request, DatabaseResponse.class, 200);
        log.info("Getting database by classifier = {}", classifier);
        foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, POSTGRES_TYPE, 200);
        log.info("Checking that database connection properties and name is updated");
        assertEquals(updatedName, updatedDatabase.getName());
        assertEquals(updatedDatabase.getName(), foundedDb.getName());
        assertEquals(updatedConnectionProperties, foundedDb.getConnectionProperties());
        assertEquals(updatedDatabase.getConnectionProperties(), updatedConnectionProperties);
        assertEquals(updatedDatabase.getPhysicalDatabaseId(), oldPhysicalDatabaseId);

        log.info("Getting all databases in namespace = {}", foundedDb.getNamespace());

        List<DatabaseV3> databasesAfterUpdate = getDatabasesByNamespaceWithResorces(TEST_NAMESPACE);
        DatabaseV3 databaseWithResourcesAfterUpdate = databasesAfterUpdate.stream().filter(db -> (db.getName().equals(updatedName))).findFirst().get();
        assertEquals(1, databaseWithResourcesAfterUpdate.getResources().stream().filter(dbResource -> dbResource.getName().equals(updatedName)).count());

        requestBody = new UpdateConnectionPropertiesRequest();
        requestBody.setClassifier(classifier);
        requestBody.setConnectionProperties(oldConnectionProperties);
        requestBody.setPhysicalDatabaseId(oldPhysicalDatabaseId);
        requestBody.setDbName(oldName);
        updatedResources.stream().filter(dbResource -> dbResource.getName().equals(updatedName)).forEach(dbResource -> dbResource.setName(oldName));
        requestBody.setResources(updatedResources);

        request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/update-connection/%s", TEST_NAMESPACE, POSTGRES_TYPE),
                helperV3.getDbaasDbEditorAuthorization(),
                requestBody,
                "PUT");
        log.info("Sending request to update connection properties");
        updatedDatabase = helperV3.executeRequest(request, DatabaseResponse.class, 200);
        assertEquals(updatedDatabase.getName(), oldName);
        assertEquals(updatedDatabase.getConnectionProperties(), oldConnectionProperties);
        assertEquals(updatedDatabase.getPhysicalDatabaseId(), oldPhysicalDatabaseId);
    }

    @Test
    public void recreateLogicalDb() throws IOException {
        // create the first db
        Map<String, Object> classifier = new ClassifierBuilder().build();
        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 201);

        // try to get not global physical db
        RegisteredPhysicalDatabasesDTOV3 physicalDatabases = helperV3.getRegisteredPhysicalDatabases(POSTGRES_TYPE, helperV3.getClusterDbaAuthorization(), 200);
        Optional<Map.Entry<String, PhysicalDatabaseRegistrationResponseDTOV3>> notGlobalPhysicalDb = physicalDatabases.getIdentified()
                .entrySet()
                .stream()
                .filter(physDb -> !physDb.getValue().isGlobal())
                .findFirst();
        String physicalDbId;
        if (notGlobalPhysicalDb.isPresent()) {
            physicalDbId = notGlobalPhysicalDb.get().getKey();
            log.info("Find not global physical database. PhysDb id {}", physicalDbId);
        } else {
            physicalDbId = physicalDatabases.getIdentified().keySet().stream().findFirst().get();
            log.info("Find global physical database. PhysDb id {}", physicalDbId);
        }

        // recreate db
        RecreateDatabaseRequest recreateDatabaseRequest = new RecreateDatabaseRequest(POSTGRES_TYPE, classifier, physicalDbId);
        log.info("Recreate database request body {}", recreateDatabaseRequest);
        Request request = helperV3.createRequest(String.format(RECREATE_API_V3, TEST_NAMESPACE),
                helperV3.getDbaasDbEditorAuthorization(),
                Collections.singletonList(recreateDatabaseRequest),
                "POST");
        RecreateDatabaseResponseV3 recreateResponse = helperV3.executeRequest(request, RecreateDatabaseResponseV3.class, 200);
        log.info("Recreate database response body {}", recreateResponse);
        // asserts
        assertThat(recreateResponse.getUnsuccessfully(), is(empty()));
        assertThat(recreateResponse.getSuccessfully(), hasSize(1));
        DatabaseResponse newDb = recreateResponse.getSuccessfully().getFirst().getNewDb();
        assertThat(database.getClassifier(), is(equalTo(newDb.getClassifier())));
        assertThat(database.getNamespace(), is(equalTo(newDb.getNamespace())));
        assertThat(database.getConnectionProperties(), is(not(newDb.getConnectionProperties())));
        assertThat(database.getName(), is(not(newDb.getName())));

        DatabaseResponse actualDb = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 200);
        assertThat(actualDb.getClassifier(), is(equalTo(newDb.getClassifier())));
        assertThat(actualDb.getConnectionProperties().get("url"), is(newDb.getConnectionProperties().get("url")));
        assertThat(actualDb.getConnectionProperties().get("username"), is(newDb.getConnectionProperties().get("username")));
        assertThat(actualDb.getName(), is(newDb.getName()));

        DatabaseV3 archivedDb = helperV3.getDatabasesByNamespace(TEST_NAMESPACE)
                .stream()
                .filter(db -> db.getName().equals(database.getName())).findFirst().get();
        assertNotNull(archivedDb);
        assertTrue(archivedDb.getClassifier().containsKey("archived"));
    }

    @Test
    void updateClassifierWithCloneIT() throws IOException, SQLException {
        Map<String, Object> classifier = new ClassifierBuilder().build();
        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 201);

        Map<String, Object> targetClassifier = new HashMap<>();
        targetClassifier.put("tenantId", "123456789");
        targetClassifier.put("microserviceName", "service-name");
        targetClassifier.put("scope", "tenant");
        targetClassifier.put("namespace", TEST_NAMESPACE);

        UpdateClassifierRequestV3 requestBody = new UpdateClassifierRequestV3();
        requestBody.setFrom(classifier);
        requestBody.setTo(targetClassifier);
        requestBody.setClone(true);
        Request request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/update-classifier/%s", TEST_NAMESPACE, POSTGRES_TYPE),
                helperV3.getDbaasDbEditorAuthorization(),
                requestBody,
                "PUT");
        DatabaseV3 updatedDatabase = helperV3.executeRequest(request, DatabaseV3.class, 200);

        DatabaseResponse foundedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), database.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        assertEquals(database.getName(), foundedDb.getName());
        assertTrue(foundedDb.isExternallyManageable());

        Map<String, Object> expectClassifier = new HashMap<>(targetClassifier);
        assertEquals(expectClassifier, updatedDatabase.getClassifier());

        helperV3.checkConnectionPostgres(database);
        DatabaseResponse updatedDb = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), updatedDatabase.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 200);
        helperV3.checkConnectionPostgres(updatedDb);

        ClassifierWithRolesRequest classifierWithRolesRequest = new ClassifierWithRolesRequest();
        classifierWithRolesRequest.setClassifier(classifier);
        classifierWithRolesRequest.setUserRole(Role.ADMIN.getRoleValue());
        classifierWithRolesRequest.setOriginService((String) classifier.get("microserviceName"));

        helperV3.deleteDatabasesByClassifierRequest(TEST_NAMESPACE, POSTGRES_TYPE, classifierWithRolesRequest, 200);
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), database.getClassifier(), TEST_NAMESPACE, POSTGRES_TYPE, 404); //check that Db is deleted

        helperV3.checkConnectionPostgres(updatedDb);
        classifierWithRolesRequest.setClassifier(targetClassifier);
        classifierWithRolesRequest.setOriginService((String) targetClassifier.get("microserviceName"));
        helperV3.deleteDatabasesByClassifierRequest(TEST_NAMESPACE, POSTGRES_TYPE, classifierWithRolesRequest, 200);
    }

    @Test
    void linkDBToAnotherNamespace() throws IOException, SQLException {
        String targetNamespace = TEST_NAMESPACE + "_target";

        try {
            Map<String, Object> classifier = new ClassifierBuilder().test("origin").build();
            DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), classifier, POSTGRES_TYPE, false, 201);

            List<DatabaseV3> databases = helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
            assertEquals(1, databases.size());

            LinkDatabasesRequest requestBody = new LinkDatabasesRequest(List.of(TEST_MICROSERVICE_NAME), targetNamespace);
            Request request = helperV3.createRequest(String.format("api/v3/dbaas/namespaces/%s/databases/link", TEST_NAMESPACE),
                    helperV3.getDbaasDbEditorAuthorization(),
                    requestBody,
                    "POST");
            List response = helperV3.executeRequest(request, List.class, 200);

            assertEquals(1, response.size());
            Map<String, Object> connectionProperties = ((List<Map<String, Object>>) ((HashMap<String, Object>) response.getFirst()).get("connectionProperties")).stream()
                    .filter(map -> "admin".equalsIgnoreCase((String) map.get("role"))).findFirst().orElseThrow();
            ((HashMap<String, Object>) response.getFirst()).put("connectionProperties", connectionProperties);
            DatabaseResponse linkedDatabase = helperV3.objectMapper.convertValue(response.getFirst(), new TypeReference<>() {
            });

            List<DatabaseV3> oldDatabases = helperV3.getDatabasesByNamespace(TEST_NAMESPACE);
            assertEquals(1, oldDatabases.size());
            List<DatabaseV3> newDatabases = helperV3.getDatabasesByNamespace(targetNamespace);
            assertEquals(1, newDatabases.size());

            assertEquals(targetNamespace, linkedDatabase.getNamespace());
            assertEquals(database.getName(), linkedDatabase.getName());
            assertEquals(database.getClassifier().get(TEST_CLASSIFIER_KEY), linkedDatabase.getClassifier().get(TEST_CLASSIFIER_KEY));

            helperV3.checkConnectionPostgres(database, "fromOrigin", null);
            helperV3.checkConnectionPostgres(linkedDatabase, null, "fromOrigin");
        } finally {
            helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), targetNamespace);
        }
    }

    private void checkResultPasswordSeveralDb(PasswordChangeResponse passwordChangeResponse, String classifierValue, String classifierValue2, String namespace,
                                              List<DatabaseResponse> databases, String dbType) {
        assertThat(passwordChangeResponse.getFailed(), hasSize(0));
        Map<String, Object> classifier = new HashMap<>();
        classifier.put(TEST_CLASSIFIER_KEY, classifierValue);
        classifier.put("namespace", namespace);
        classifier.put("scope", "service");
        classifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        Map<String, Object> classifier2 = new HashMap<>();
        classifier2.put(TEST_CLASSIFIER_KEY, classifierValue2);
        classifier2.put("namespace", namespace);
        classifier2.put("microserviceName", TEST_MICROSERVICE_NAME);
        classifier2.put("scope", "service");
        long count = passwordChangeResponse.getChanged().stream().filter(passwordChanged -> passwordChanged.getClassifier().equals(classifier) || passwordChanged.getClassifier().equals(classifier2)).count();
        assertEquals(passwordChangeResponse.getChanged().size(), count);
        passwordChangeResponse.getChanged().forEach(passwordChanged -> {
            try {
                checkResultPasswordChange(passwordChanged, databases, dbType, (String) passwordChanged.getClassifier().get(TEST_CLASSIFIER_KEY), (String) passwordChanged.getConnection().get("role"));
            } catch (IOException e) {
                fail();
            }
        });
    }

    private void checkResultPasswordChangeOneDb(PasswordChangeResponse passwordChangeResponse, List<DatabaseResponse> databases, String classifierValue, String type) throws IOException {
        assertThat(passwordChangeResponse.getChanged(), hasSize(1));
        assertThat(passwordChangeResponse.getFailed(), hasSize(0));
        checkResultPasswordChange(passwordChangeResponse.getChanged().getFirst(), databases, type, classifierValue);
    }

    private void checkResultPasswordChange(PasswordChanged passwordChanged, List<DatabaseResponse> databases, String type, String classifierValue, String role) throws IOException {
        Map<String, Object> classifier = new HashMap<>();
        classifier.put(TEST_CLASSIFIER_KEY, classifierValue);
        classifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        classifier.put("scope", "service");
        classifier.put("namespace", TEST_NAMESPACE);
        DatabaseResponse databaseWithUpdatedPassword = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, type, 200, role);
        checkPasswordChangeWithUpdatedDatbaase(passwordChanged, databases, type, classifier, databaseWithUpdatedPassword);
    }

    private void checkResultPasswordChange(PasswordChanged passwordChanged, List<DatabaseResponse> databases, String type, String classifierValue) throws IOException {
        Map<String, Object> classifier = new HashMap<>();
        classifier.put(TEST_CLASSIFIER_KEY, classifierValue);
        classifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        classifier.put("scope", "service");
        classifier.put("namespace", TEST_NAMESPACE);
        DatabaseResponse databaseWithUpdatedPassword = helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, type, 200);
        checkPasswordChangeWithUpdatedDatbaase(passwordChanged, databases, type, classifier, databaseWithUpdatedPassword);
    }

    private void checkPasswordChangeWithUpdatedDatbaase(PasswordChanged passwordChanged, List<DatabaseResponse> databases, String type, Map<String, Object> classifier, DatabaseResponse databaseWithUpdatedPassword) {
        log.info("Database with updated password {}", databaseWithUpdatedPassword);
        classifier.put("namespace", TEST_NAMESPACE);
        assertEquals(classifier, databaseWithUpdatedPassword.getClassifier());
        assertEquals(type, databaseWithUpdatedPassword.getType());
        Map<String, Object> updatedConnProps = new HashMap<>(databaseWithUpdatedPassword.getConnectionProperties());
        updatedConnProps.remove("tls");
        updatedConnProps.remove("roHost");
        assertEquals(passwordChanged.getConnection().entrySet(), updatedConnProps.entrySet());
        DatabaseResponse databaseWithOldPassword = getBeforeUpdatePasswordDb(databases, classifier, type, (String) passwordChanged.getConnection().get("role"));
        assertEquals(classifier, databaseWithOldPassword.getClassifier());
        assertEquals(type, databaseWithOldPassword.getType());
        log.info("Resources of update database {}", databaseWithUpdatedPassword.getResources());
        assertEquals(databaseWithOldPassword.getResources(), databaseWithUpdatedPassword.getResources());
        assertNotEquals(databaseWithOldPassword.getConnectionProperties().get("password"),
                databaseWithUpdatedPassword.getConnectionProperties().get("password"));
        Map<String, Object> oldConnection = new HashMap<>(databaseWithOldPassword.getConnectionProperties());
        Map<String, Object> newConnection = new HashMap<>(databaseWithUpdatedPassword.getConnectionProperties());
        oldConnection.remove("password");
        newConnection.remove("password");
        assertEquals(oldConnection, newConnection);
    }


    private DatabaseResponse getBeforeUpdatePasswordDb(List<DatabaseResponse> databases, Map<String, Object> classifier, String type) {
        return databases.stream().filter(database -> database.getType().equals(type) && database.getClassifier().equals(classifier))
                .findFirst()
                .orElseThrow(NullPointerException::new);
    }

    private DatabaseResponse getBeforeUpdatePasswordDb(List<DatabaseResponse> databases, Map<String, Object> classifier, String type, String role) {
        return databases.stream().filter(database -> database.getType().equals(type) && database.getClassifier().equals(classifier) &&
                        role.equalsIgnoreCase((String) database.getConnectionProperties().get("role")))
                .findFirst()
                .orElseThrow(NullPointerException::new);
    }

    private PasswordChangeResponse changePassword(String classifierValue, String dbType, int expectHttpCode, String namespace) throws IOException {
        Map<String, Object> classifier = null;
        if (classifierValue != null) {
            classifier = new TreeMap<>();
            classifier.put(TEST_CLASSIFIER_KEY, classifierValue);
            classifier.put("microserviceName", TEST_MICROSERVICE_NAME);
            classifier.put("scope", "service");
            classifier.put("namespace", TEST_NAMESPACE);
        }
        return helperV3.changePassword(classifier, dbType, expectHttpCode, namespace);
    }

    public List<DatabaseV3> getDatabasesByNamespaceWithResorces(String testNamespace) {
        Request request = helperV3.createRequest(String.format("api/v3/dbaas/%s/databases/list?withResources=true", testNamespace), helperV3.getClusterDbaAuthorization(), null, "GET");
        return Arrays.asList(helperV3.executeRequest(request, DatabaseV3[].class, 200));
    }
}
