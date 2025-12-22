package com.netcracker.it.dbaas.test.declarative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.dbaas.entity.DatabaseCreateRequestV3;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.Role;
import com.netcracker.it.dbaas.entity.config.DatabaseDeclaration;
import com.netcracker.it.dbaas.entity.config.DeclarativeDatabaseCreationRequest;
import com.netcracker.it.dbaas.entity.config.SecurityRuleConfigurationRequest;
import com.netcracker.it.dbaas.entity.config.ServiceRole;
import com.netcracker.it.dbaas.entity.response.AccessGrantsResponse;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DbaasHelperV3;
import com.netcracker.it.dbaas.helpers.DeclarativeConfigHelper;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Slf4j
public class DeclarativeIT extends AbstractIT {
    private static DeclarativeConfigHelper declarativeHelper;

    @BeforeAll
    public static void setUp() {
        declarativeHelper = new DeclarativeConfigHelper(helperV3);
    }

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    public void postgresTestDatabaseCreatedAndConnecting() throws IOException, SQLException {
        helperV3.createServicesRoles(POSTGRES_TYPE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("ro-service"), 403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest("rw-service"), 403);
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimplePostgresCreateRequest(TEST_DECLARATIVE_MICROSERVICE_NAME), 201);
        log.info("Create database");
        log.info("Check connection to created database");
        helperV3.checkConnectionPostgres(created_1);
    }

    @Test
    public void mongoTestDatabaseCreatedAndConnecting() throws IOException {
        Assumptions.assumeTrue(helperV3.hasAdapterOfType(MONGODB_TYPE), "No mongo adapter. Skip test.");
        helperV3.createServicesRoles(MONGODB_TYPE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleMongoCreateRequest("ro-service"), 403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleMongoCreateRequest("rw-service"), 403);
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleMongoCreateRequest(TEST_DECLARATIVE_MICROSERVICE_NAME), 201);
        log.info("Create database");
        log.info("Check connection to created database");
        helperV3.checkConnectionMongo(created_1);
    }

    @Test
    public void cassandraTestDatabaseCreatedAndConnecting() throws IOException {
        Assumptions.assumeTrue(helperV3.hasAdapterOfType(CASSANDRA_TYPE), "No cassandra adapter. Skip test.");
        helperV3.createServicesRoles(CASSANDRA_TYPE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleCassandraCreateRequest("ro-service"), 403);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleCassandraCreateRequest("rw-service"), 403);
        DatabaseResponse created_1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE), getSimpleCassandraCreateRequest(TEST_DECLARATIVE_MICROSERVICE_NAME), 201);
        log.info("Create database");
        log.info("Check connection to created database");
        helperV3.checkConnectionCassandra(created_1, "test", "test");
    }

    @Test
    void getAccessGrantsTest() {
        ServiceRole rwServiceRole = new ServiceRole();
        rwServiceRole.setName("rw-service");
        rwServiceRole.setRoles(Collections.singletonList(Role.RW.getRoleValue()));

        ServiceRole roServiceRole = new ServiceRole();
        roServiceRole.setName("ro-service");
        roServiceRole.setRoles(Collections.singletonList("ro"));
        List<ServiceRole> services = Arrays.asList(rwServiceRole, roServiceRole);

        helperV3.createServicesRoles(POSTGRES_TYPE, false);
        log.info("Get access grants for service");
        AccessGrantsResponse accessGrantsResponse = helperV3.getAccessRoles(TEST_NAMESPACE,
                TEST_DECLARATIVE_MICROSERVICE_NAME,
                200);
        Assertions.assertEquals(accessGrantsResponse.getServices(), services);
        Assertions.assertFalse(accessGrantsResponse.getDisableGlobalPermissions());

        log.info("Try to get grants for non-existent service");
        helperV3.getAccessRoles(TEST_NAMESPACE, "wrong-service", 404);
    }

    @Test
    void testDeclarativeConfigurationOnlyDbCreateRequest() throws IOException, InterruptedException {
        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", TEST_DECLARATIVE_MICROSERVICE_NAME);
        classifier.put("scope", "service");
        classifier.put("custom_keys", Collections.singletonMap("logicalDBName", "configs"));
        DeclarativeDatabaseCreationRequest databaseCreationRequest = declarativeHelper.getDeclarativeDatabaseCreationRequest(classifier);

        Request request = helperV3.createRequest(String.format(DECLARATIVE_SERVICE_URL, DbaasHelperV3.TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME),
                helperV3.getClusterDbaAuthorization(),
                databaseCreationRequest,
                "POST");

        String trackingId;
        try (Response responseBody = helperV3.executeRequest(request, 202)) {
            JsonNode node = objectMapper.readTree(responseBody.body().string());
            trackingId = node.get("trackingId").asText();
        }

        classifier.put("namespace", TEST_NAMESPACE);
        try (Response applyConfigStatus = declarativeHelper.awaitApplyConfigOld(trackingId, 30)) {
            Assertions.assertEquals(200, applyConfigStatus.code());
        }
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, POSTGRES_TYPE, 200);
    }

    @Test
    @Tag("Smoke")
    void testDeclarativeConfigurationCompositeRequest() throws IOException, InterruptedException {
        SecurityRuleConfigurationRequest rolesRegistrationRequest = helperV3.getRolesRegistrationRequest(POSTGRES_TYPE, false);
        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", TEST_DECLARATIVE_MICROSERVICE_NAME);
        classifier.put("scope", "service");
        classifier.put("custom_keys", Collections.singletonMap("logicalDBName", "configs"));
        DeclarativeDatabaseCreationRequest databaseCreationRequest = declarativeHelper.getDeclarativeDatabaseCreationRequest(classifier);

        List<Object> listRequest = new ArrayList<>();
        listRequest.add(rolesRegistrationRequest);
        listRequest.add(databaseCreationRequest);

        Request request = helperV3.createRequest(String.format(DECLARATIVE_SERVICE_URL, DbaasHelperV3.TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME),
                helperV3.getClusterDbaAuthorization(),
                listRequest,
                "POST");

        String trackingId;
        try (Response responseBody = helperV3.executeRequest(request, 202)) {
            JsonNode node = objectMapper.readTree(responseBody.body().string());
            trackingId = node.get("trackingId").asText();
        }

        classifier.put("namespace", TEST_NAMESPACE);
        try (Response applyConfigStatus = declarativeHelper.awaitApplyConfigOld(trackingId, 30)) {
            Assertions.assertEquals(200, applyConfigStatus.code());
        }
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, POSTGRES_TYPE, 200);
    }

    @Test
    @Tag("backup")
    void testDeclarativeCloneDatabaseRequest() throws IOException, InterruptedException, SQLException {
        DatabaseResponse firstService = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                getSimplePostgresCreateRequest(TEST_DECLARATIVE_MICROSERVICE_NAME), 201);

        helperV3.checkConnectionPostgres(firstService, "toClone", "toClone");

        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", TEST_DECLARATIVE_MICROSERVICE_NAME);
        classifier.put("scope", "service");
        classifier.put("custom_keys", Collections.singletonMap("logicalDBName", "configs"));
        DeclarativeDatabaseCreationRequest databaseCreationRequest = declarativeHelper.getDeclarativeDatabaseCreationRequest(classifier);
        databaseCreationRequest.getDeclarations().getFirst().getInitialInstantiation().setApproach("clone");

        Map<String, Object> testClassifier = new ClassifierBuilder().test("dbaas_auto_test_1").ms(TEST_DECLARATIVE_MICROSERVICE_NAME).build();
        databaseCreationRequest.getDeclarations().getFirst().getInitialInstantiation().setSourceClassifier(testClassifier);
        List<Object> listRequest = new ArrayList<>();

        listRequest.add(databaseCreationRequest);

        Request request = helperV3.createRequest(String.format(DECLARATIVE_SERVICE_URL, DbaasHelperV3.TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME),
                helperV3.getClusterDbaAuthorization(),
                listRequest,
                "POST");
        String trackingId;
        try (Response responseBody = helperV3.executeRequest(request, 202)) {
            JsonNode node = objectMapper.readTree(responseBody.body().string());
            trackingId = node.get("trackingId").asText();
        }

        classifier.put("namespace", TEST_NAMESPACE);
        try (Response applyConfigStatus = declarativeHelper.awaitApplyConfigOld(trackingId, 120)) {
            Assertions.assertEquals(200, applyConfigStatus.code());
        }

        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                DatabaseCreateRequestV3.builder(classifier, POSTGRES_TYPE).originService(TEST_DECLARATIVE_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(database, null, "toClone");
        helperV3.checkConnectionPostgres(database, "test2", "test2");

        checkDatabaseDoesNotHaveValue(firstService, "test2");
    }

    @Test
    @Tag("backup")
    void testDeclarativeCloneDatabaseRequestWithSourceInDeclaration() throws IOException, InterruptedException, SQLException {
        Map<String, Object> sourceDbClassifier = new HashMap<>();
        sourceDbClassifier.put("microserviceName", TEST_DECLARATIVE_MICROSERVICE_NAME);
        sourceDbClassifier.put("namespace", TEST_NAMESPACE);
        sourceDbClassifier.put("scope", "service");
        sourceDbClassifier.put("dbaas_auto_test", "dbaas_auto_test_1");
        DeclarativeDatabaseCreationRequest sourceDbCreationRequest = declarativeHelper.getDeclarativeDatabaseCreationRequest(sourceDbClassifier);
        sourceDbCreationRequest.getDeclarations().getFirst().setInitialInstantiation(null);
        sourceDbCreationRequest.getDeclarations().getFirst().setVersioningConfig(new DatabaseDeclaration.VersioningConfig() {
            {
                this.approach = "new";
            }
        });
        sendDeclarativeRequest(sourceDbCreationRequest);

        DatabaseCreateRequestV3 simplePostgresCreateRequest = getSimplePostgresCreateRequest(TEST_DECLARATIVE_MICROSERVICE_NAME);
        DatabaseResponse firstService = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                simplePostgresCreateRequest, 200);
        helperV3.checkConnectionPostgres(firstService, "toClone", "toClone");

        Map<String, Object> cloneDbClassifier = new HashMap<>();
        cloneDbClassifier.put("microserviceName", TEST_DECLARATIVE_MICROSERVICE_NAME);
        cloneDbClassifier.put("namespace", TEST_NAMESPACE);
        cloneDbClassifier.put("scope", "service");
        cloneDbClassifier.put("custom_keys", Collections.singletonMap("logicalDBName", "configs"));
        DeclarativeDatabaseCreationRequest cloneDbCreationRequest = declarativeHelper.getDeclarativeDatabaseCreationRequest(cloneDbClassifier);
        cloneDbCreationRequest.getDeclarations().getFirst().getInitialInstantiation().setApproach("clone");
        Map<String, Object> testClassifier = new ClassifierBuilder().test("dbaas_auto_test_1").ms(TEST_DECLARATIVE_MICROSERVICE_NAME).build();
        cloneDbCreationRequest.getDeclarations().getFirst().getInitialInstantiation().setSourceClassifier(testClassifier);

        sourceDbCreationRequest.getDeclarations().getFirst().setVersioningConfig(new DatabaseDeclaration.VersioningConfig());
        cloneDbCreationRequest.getDeclarations().addFirst(sourceDbCreationRequest.getDeclarations().getFirst());
        sendDeclarativeRequest(cloneDbCreationRequest);

        DatabaseResponse database = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                DatabaseCreateRequestV3.builder(cloneDbClassifier, POSTGRES_TYPE).originService(TEST_DECLARATIVE_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(database, null, "toClone");
        helperV3.checkConnectionPostgres(database, "test2", "test2");

        checkDatabaseDoesNotHaveValue(firstService, "test2");
    }

    private void sendDeclarativeRequest(DeclarativeDatabaseCreationRequest databaseCreationRequest) throws IOException, InterruptedException {
        Request request = helperV3.createRequest(String.format(DECLARATIVE_SERVICE_URL, DbaasHelperV3.TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME),
                helperV3.getClusterDbaAuthorization(),
                List.of(databaseCreationRequest),
                "POST");
        String trackingId;
        try (Response responseBody = helperV3.executeRequest(request, 202)) {
            JsonNode node = objectMapper.readTree(responseBody.body().string());
            trackingId = node.get("trackingId").asText();
        }

        try (Response applyConfigStatus = declarativeHelper.awaitApplyConfigOld(trackingId, 120)) {
            Assertions.assertEquals(200, applyConfigStatus.code());
        }
    }

    @Test
    void testDeclarativeConfigurationWrongConfigKind() throws IOException {
        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", TEST_DECLARATIVE_MICROSERVICE_NAME);
        classifier.put("scope", "service");
        classifier.put("custom_keys", Collections.singletonMap("logicalDBName", "configs"));
        DeclarativeDatabaseCreationRequest databaseCreationRequest = declarativeHelper.getDeclarativeDatabaseCreationRequest(classifier);
        databaseCreationRequest.setKind("someWrongKind");

        Request request = helperV3.createRequest(String.format(DECLARATIVE_SERVICE_URL, DbaasHelperV3.TEST_NAMESPACE, TEST_DECLARATIVE_MICROSERVICE_NAME),
                helperV3.getClusterDbaAuthorization(),
                databaseCreationRequest,
                "POST");
        helperV3.executeRequest(request, null, 400);

        classifier.put("namespace", TEST_NAMESPACE);
        helperV3.getDatabaseByClassifierAsPOJO(helperV3.getClusterDbaAuthorization(), classifier, TEST_NAMESPACE, POSTGRES_TYPE, 404);
    }


    private DatabaseCreateRequestV3 getSimplePostgresCreateRequest(String originService) {
        return helperV3.getCreateDeclarativeDatabaseRequest(originService, POSTGRES_TYPE, null);
    }

    private DatabaseCreateRequestV3 getSimpleMongoCreateRequest(String originService) {
        return helperV3.getCreateDeclarativeDatabaseRequest(originService, MONGODB_TYPE, null);
    }

    private DatabaseCreateRequestV3 getSimpleCassandraCreateRequest(String originService) {
        return helperV3.getCreateDeclarativeDatabaseRequest(originService, CASSANDRA_TYPE, null);
    }

    private static void checkDatabaseDoesNotHaveValue(DatabaseResponse databaseResponse, String checkData) throws SQLException, IOException {
        boolean isServiceChanged = true;
        try {
            helperV3.checkConnectionPostgres(databaseResponse, null, checkData);
        } catch (AssertionError assertionError) {
            log.info("expected exception");
            isServiceChanged = false;
        }
        assertFalse(isServiceChanged);
    }
}
