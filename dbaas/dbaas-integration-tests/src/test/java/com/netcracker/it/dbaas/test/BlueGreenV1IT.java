package com.netcracker.it.dbaas.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.junit.cloudcore.extension.service.ServicePortForwardParams;
import com.netcracker.it.dbaas.entity.*;
import com.netcracker.it.dbaas.entity.backup.v3.NamespaceBackupV3;
import com.netcracker.it.dbaas.entity.config.DeclarativePayload;
import com.netcracker.it.dbaas.entity.response.AccessGrantsResponse;
import com.netcracker.it.dbaas.helpers.*;
import io.fabric8.kubernetes.api.model.Service;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static com.netcracker.it.dbaas.entity.config.DatabaseDeclaration.DeclarativeDBConfigBuilder;
import static com.netcracker.it.dbaas.entity.config.RolesRegistration.DeclarativeRoleConfigBuilder;
import static com.netcracker.it.dbaas.helpers.BGHelper.*;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@Tag("bg")
class BlueGreenV1IT extends AbstractIT {
    protected final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(1));
    private final static String TEST_NAMESPACE_ACTIVE = "active-test-namespace";

    private final static String TEST_NAMESPACE_CANDIDATE = "candidate-test-namespace";

    private static PaasHelper paasHelper;
    private static BGHelper bgHelper;
    private static BalancingRulesHelperV3 balancingRulesHelperV3;
    private static DeclarativeConfigHelper declarativeHelper;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void setUp() {
        paasHelper = new PaasHelper(kubernetesClient);
        bgHelper = new BGHelper(helperV3);
        balancingRulesHelperV3 = new BalancingRulesHelperV3(helperV3);
        declarativeHelper = new DeclarativeConfigHelper(helperV3);
    }

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE_ACTIVE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE_CANDIDATE);
        bgHelper.destroyDomain(new BgNamespaceRequest(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)).close();
    }

    @Test
    void testInitDomain() throws IOException {
        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);
    }

    @Test
    void testListDomains() throws IOException {
        createBgDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, TEST_NAMESPACE_CONTROLLER);
        createBgDomain(TEST_NAMESPACE_ACTIVE + "_2", TEST_NAMESPACE_CANDIDATE + "_2", TEST_NAMESPACE_CONTROLLER + "_2");

        try (Response listResponse = bgHelper.listDomains()) {
            Assertions.assertEquals(200, listResponse.code());
            String responseBody = listResponse.body().string();
            log.info("List domains response: {}", responseBody);
            List<BgDomainForList> domains = objectMapper.readValue(responseBody, new TypeReference<>() {
            });
            Assertions.assertTrue(domains.stream().anyMatch(domain -> TEST_NAMESPACE_CONTROLLER.equals(domain.getControllerNamespace())
                    && TEST_NAMESPACE_ACTIVE.equals(domain.getOriginNamespace())
                    && TEST_NAMESPACE_CANDIDATE.equals(domain.getPeerNamespace())), "Can't find correct first domain");
            Assertions.assertTrue(domains.stream().anyMatch(domain -> (TEST_NAMESPACE_CONTROLLER + "_2").equals(domain.getControllerNamespace())
                    && (TEST_NAMESPACE_ACTIVE + "_2").equals(domain.getOriginNamespace())
                    && (TEST_NAMESPACE_CANDIDATE + "_2").equals(domain.getPeerNamespace())), "Can't find correct second domain");
        } finally {
            bgHelper.destroyDomain(new BgNamespaceRequest(TEST_NAMESPACE_ACTIVE + "_2", TEST_NAMESPACE_CANDIDATE + "_2")).close();
        }
    }

    @Test
    void testDoubleInitDomain() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        try (Response secondInitResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, secondInitResponse.code());
        }

        intiDomain(TEST_NAMESPACE_CANDIDATE, TEST_NAMESPACE_ACTIVE, 409);
    }

    @Test
    void testInitDomainWithDBsInPeer() throws IOException {
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 201);

        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(409, initResponse.code());
        }

        Response domainResponse = getDomain(TEST_NAMESPACE_ACTIVE);
        Assertions.assertEquals(404, domainResponse.code());
    }

    @Test
    void testDestroyDomain() throws IOException {
        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);

        try (Response response = bgHelper.destroyDomain(new BgNamespaceRequest(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE))) {
            Assertions.assertEquals(200, response.code());
        }

        Response domainResponse = getDomain(TEST_NAMESPACE_ACTIVE);
        Assertions.assertEquals(404, domainResponse.code());
    }

    @Test
    void testInitDomainWithUpdateVersionsAfterDatabaseCreation() throws IOException, SQLException {
        applyDeclarativeDBConfig("toClone", "service", "clone");

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 200);

        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);

        helperV3.checkConnectionPostgres(activeDatabase, "toClone", "toClone");

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertNotEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());

        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");

        checkDatabaseDoesNotHaveValue(activeDatabase, "test2");
    }

    @Test
    void testInitDomainWithUpdateVersionsBeforeDatabaseCreation() throws IOException, SQLException {
        applyDeclarativeDBConfig("toClone", "service", "clone");

        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 200);

        helperV3.checkConnectionPostgres(activeDatabase, "toClone", "toClone");

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertNotEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());

        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");

        checkDatabaseDoesNotHaveValue(activeDatabase, "test2");
    }

    @Test
    public void testDeleteAllDatabasesInNamespaceUsingOldAPIBlueGreen() throws Exception {
        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);
        log.info("Create database");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 201);
        List<DatabaseV3> databasesInNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
        assertThat(databasesInNamespace, hasSize(1));

        checkDatabaseDeletion(DATABASES_V3, TEST_NAMESPACE_ACTIVE);

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
            List<DatabaseV3> databasesInCandidateNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
            assertEquals(0, databasesInCandidateNamespace.size());
        });
    }

    @Test
    public void testDeleteAllDatabasesInNamespaceUsingNewAPIBlueGreen() throws Exception {
        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);
        log.info("Create database");
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 201);
        List<DatabaseV3> databasesInNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
        assertThat(databasesInNamespace, hasSize(1));

        checkDatabaseDeletion(DATABASES_DELETE_V3, TEST_NAMESPACE_ACTIVE);

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
            List<DatabaseV3> databasesInCandidateNamespace = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
            assertEquals(0, databasesInCandidateNamespace.size());
        });

    }

    private void intiDomain(String testNamespaceActive, String testNamespaceCandidate, int expected) throws IOException {
        try (Response initResponse = bgHelper.initDomain(testNamespaceActive, testNamespaceCandidate)) {
            Assertions.assertEquals(expected, initResponse.code());
        }
        checkBgDomainStatus(ACTIVE_STATE, IDLE_STATE);
    }

    private void checkDatabaseDeletion(String URL, String namespace) throws Exception {
        String clusterDbaAuthorization = helperV3.getClusterDbaAuthorization();
        helperV3.deleteDatabases(URL, clusterDbaAuthorization, namespace, HttpStatus.SC_OK);
        var databasesInNamespace = helperV3.getDatabasesByNamespace(namespace);
        assertThat(databasesInNamespace, hasSize(0));
    }


    @Test
    @Tag("backup")
    @Tag("Smoke")
    void testDatabaseToVersion() throws IOException, SQLException {
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 201);

        intiDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE, 200);

        applyDeclarativeDBConfig("toClone", "service", "clone");

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 200);

        helperV3.checkConnectionPostgres(activeDatabase, "toClone", "toClone");
        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertNotEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());

        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");

        checkDatabaseDoesNotHaveValue(activeDatabase, "test2");
    }

    @Test
    void testPromote() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        try (Response response = bgHelper.promoteDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            assertTrue(response.isSuccessful());
        }

        checkBgDomainStatus(LEGACY_STATE, ACTIVE_STATE);
    }


    @Test
    void testDeleteOrphanDBs() throws IOException {
        try {
            paasHelper.toProdMode();
            Service service = kubernetesClient.services().withName(DBAAS_SERVICE_NAME).get();
            int targetPort = service.getSpec().getPorts().getFirst().getTargetPort().getIntVal();
            portForwardService.closePortForwards();
            portForwardService.portForward(ServicePortForwardParams.builder(DBAAS_SERVICE_NAME, targetPort).build());

            try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
                Assertions.assertEquals(200, initResponse.code());
            }

            applyDeclarativeDBConfig("toNew", "service", "new");

            doWarmup();
            List<DatabaseV3> candidateNamespaces = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
            Assertions.assertEquals(1, candidateNamespaces.size());
            List<DatabaseV3> activeNamespaces = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_ACTIVE);
            Assertions.assertEquals(1, activeNamespaces.size());

            try (Response commitResponse = bgHelper.commitDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
                Assertions.assertEquals(200, commitResponse.code());
            }
            List<DatabaseV3> candidateNamespacesAfterCommit = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
            Assertions.assertEquals(1, candidateNamespacesAfterCommit.size());
            Assertions.assertEquals("MARKED_FOR_DROP", candidateNamespacesAfterCommit.getFirst().getClassifier().get("MARKED_FOR_DROP"));
            List<DatabaseV3> activeNamespaceAfterCommit = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_ACTIVE);
            Assertions.assertEquals(1, activeNamespaceAfterCommit.size());
            Assertions.assertNull(activeNamespaceAfterCommit.getFirst().getClassifier().get("MARKED_FOR_DROP"));

            List<String> namespacesWithOrphanDB = List.of(TEST_NAMESPACE_CANDIDATE);
            try (Response getOrphansResponse = bgHelper.getOrphans(namespacesWithOrphanDB)) {
                Assertions.assertEquals(200, getOrphansResponse.code());
                JsonNode node = objectMapper.readTree(getOrphansResponse.body().string());
                Assertions.assertEquals(1, node.size());
                Assertions.assertEquals(candidateNamespacesAfterCommit.getFirst().getName(), node.get(0).get("dbName").asText());
            }

            try (Response cleanupOrphansResponse = bgHelper.cleanupOrphans(namespacesWithOrphanDB, true)) {
                Assertions.assertEquals(200, cleanupOrphansResponse.code());
                JsonNode node = objectMapper.readTree(cleanupOrphansResponse.body().string());
                Assertions.assertEquals(1, node.size());
                Assertions.assertEquals(candidateNamespacesAfterCommit.getFirst().getName(), node.get(0).get("dbName").asText());
            }
            Failsafe.with(OPERATION_STATUS_RETRY_POLICY).run(() -> {
                List<DatabaseV3> candidateNamespacesAfterCleanup = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
                Assertions.assertTrue(candidateNamespacesAfterCleanup.isEmpty());
            });
            List<DatabaseV3> activeNamespacesAfterCleanup = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_ACTIVE);
            Assertions.assertEquals(1, activeNamespacesAfterCleanup.size());
        } finally {
            paasHelper.toDevMode();
            Service service = kubernetesClient.services().withName(DBAAS_SERVICE_NAME).get();
            int targetPort = service.getSpec().getPorts().getFirst().getTargetPort().getIntVal();
            portForwardService.closePortForwards();
            portForwardService.portForward(ServicePortForwardParams.builder(DBAAS_SERVICE_NAME, targetPort).build());
        }
    }

    @Test
    void testRollback() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        try (Response response = bgHelper.promoteDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            assertTrue(response.isSuccessful());
        }
        checkBgDomainStatus(LEGACY_STATE, ACTIVE_STATE);

        try (Response response = bgHelper.rollbackDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            assertTrue(response.isSuccessful());
        }
        checkBgDomainStatus(ACTIVE_STATE, CANDIDATE_STATE);
    }

    private void checkBgDomainStatus(String legacyState, String activeState) throws IOException {
        Response domainResponse = getDomain(TEST_NAMESPACE_ACTIVE);
        String body = domainResponse.body().string();
        BgDomain bgDomain = objectMapper.readValue(body, BgDomain.class);
        assertEquals(2, bgDomain.getNamespaces().size());

        Optional<BgNamespace> activeNamespace = bgDomain.getNamespaces().stream().filter(bgNamespace -> bgNamespace.getNamespace().equals(TEST_NAMESPACE_ACTIVE)).findFirst();
        Assertions.assertTrue(activeNamespace.isPresent());
        Assertions.assertEquals(legacyState, activeNamespace.get().getState());

        Optional<BgNamespace> idleNamespace = bgDomain.getNamespaces().stream().filter(bgNamespace -> bgNamespace.getNamespace().equals(TEST_NAMESPACE_CANDIDATE)).findFirst();
        Assertions.assertTrue(idleNamespace.isPresent());
        Assertions.assertEquals(activeState, idleNamespace.get().getState());
    }

    @Test
    void testWarmupDomain() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {

            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        checkBgDomainStatus(ACTIVE_STATE, CANDIDATE_STATE);
    }

    @Test
    @Tag("backup")
    void testWarmupTerminate() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        DeclarativePayload payload = new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("toClone"))
                .versioning("clone")
                .build().asPayload(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(payload);

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 200);
        helperV3.checkConnectionPostgres(activeDatabase, "toClone", "toClone");
        String finalTrackingId;
        try (Response warmupResponse = bgHelper.warmupDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(202, warmupResponse.code());
            JsonNode node = objectMapper.readTree(warmupResponse.body().string());
            finalTrackingId = node.get("trackingId").asText();
        }
        Response response = bgHelper.terminateOperation(finalTrackingId);
        response.close();

        try (Response operationStatus = bgHelper.getOperationStatus(finalTrackingId)) {
            JsonNode node = objectMapper.readTree(operationStatus.body().string());
            assertEquals("terminated", node.get("status").asText());
        }

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 201);
        checkDatabaseDoesNotHaveValue(candidateDatabase, "toClone");
    }


    @SneakyThrows
    @Test
    @Tag("backup")
    void testWarmupTerminateAndRetry() {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        DeclarativePayload payload = new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("toClone"))
                .versioning("clone")
                .build().asPayload(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(payload);

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 200);
        helperV3.checkConnectionPostgres(activeDatabase, "toClone", "toClone");
        String finalTrackingId;
        try (Response warmupResponse = bgHelper.warmupDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(202, warmupResponse.code());
            JsonNode node = objectMapper.readTree(warmupResponse.body().string());
            finalTrackingId = node.get("trackingId").asText();
        }
        Response response = bgHelper.terminateOperation(finalTrackingId);
        response.close();

        try (Response operationStatus = bgHelper.getOperationStatus(finalTrackingId)) {
            JsonNode node = objectMapper.readTree(operationStatus.body().string());
            assertEquals("terminated", node.get("status").asText());
        }

        Thread.sleep(5000);

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);
        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
    }

    @Test
    void testWarmupDomainDatabaseCreation() throws IOException, SQLException {
        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 201);

        helperV3.checkConnectionPostgres(activeDatabase, "test1", "test1");

        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertEquals(activeDatabase.getName(), candidateDatabase.getName());

        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());

        helperV3.checkConnectionPostgres(candidateDatabase, null, "test1");
        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");

        helperV3.checkConnectionPostgres(activeDatabase, null, "test2");
    }

    @Test
    void testAccessViaControllerNamespace() throws IOException, SQLException {
        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 201);

        helperV3.checkConnectionPostgres(activeDatabase, "test1", "test1");

        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 200);
        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 200);
        DatabaseResponse controllerDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CONTROLLER),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CONTROLLER), 200);

        Assertions.assertEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertEquals(activeDatabase.getName(), controllerDatabase.getName());

        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());
        Assertions.assertEquals(activeDatabase.getClassifier(), controllerDatabase.getClassifier());

        helperV3.checkConnectionPostgres(candidateDatabase, null, "test1");
        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");

        helperV3.checkConnectionPostgres(activeDatabase, null, "test2");
        helperV3.checkConnectionPostgres(controllerDatabase, null, "test2");

        DatabaseResponse databaseByActiveClassifier = helperV3.getDatabaseByClassifierAsPOJO(
                helperV3.getClusterDbaAuthorization(), activeDatabase.getClassifier(), TEST_NAMESPACE_CONTROLLER, POSTGRES_TYPE, 200);
        DatabaseResponse databaseByCandidateClassifier = helperV3.getDatabaseByClassifierAsPOJO(
                helperV3.getClusterDbaAuthorization(), candidateDatabase.getClassifier(), TEST_NAMESPACE_CONTROLLER, POSTGRES_TYPE, 200);
        Map<String, Object> controllerClassifier = new HashMap<>(controllerDatabase.getClassifier());
        controllerClassifier.put("namespace", TEST_NAMESPACE_CONTROLLER);
        DatabaseResponse databaseByControllerClassifier = helperV3.getDatabaseByClassifierAsPOJO(
                helperV3.getClusterDbaAuthorization(), controllerClassifier, TEST_NAMESPACE_CONTROLLER, POSTGRES_TYPE, 200);

        Assertions.assertEquals(activeDatabase.getClassifier(), databaseByActiveClassifier.getClassifier());
        Assertions.assertEquals(candidateDatabase.getClassifier(), databaseByCandidateClassifier.getClassifier());
        Assertions.assertEquals(controllerDatabase.getClassifier(), databaseByControllerClassifier.getClassifier());
    }

    @Test
    @Tag("backup")
    void testWarmupDeclarativeDatabaseClone() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        applyDeclarativeDBConfig("toClone", "service", "clone");

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_ACTIVE), 200);
        helperV3.checkConnectionPostgres(activeDatabase, "toClone", "toClone");
        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertNotEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());

        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");

        checkDatabaseDoesNotHaveValue(activeDatabase, "test2");
    }

    @Test
    void testWarmupDeclarativeDatabaseNew() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        applyDeclarativeDBConfig("toNew", "service", "new");

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("toNew", TEST_NAMESPACE_ACTIVE), 200);
        helperV3.checkConnectionPostgres(activeDatabase, "toNew", "toNew");

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toNew", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertNotEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());
        checkDatabaseDoesNotHaveValue(candidateDatabase, "toNew");

        helperV3.checkConnectionPostgres(candidateDatabase, "test2", "test2");
        checkDatabaseDoesNotHaveValue(activeDatabase, "test2");
    }

    @Test
    void testWarmupDeclarativeTenantDatabaseNew() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }
        Map<String, Object> tenantClassifier1 = new HashMap<>();
        tenantClassifier1.put("tenantId", "1234");
        tenantClassifier1.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier1.put("scope", "tenant");
        tenantClassifier1.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);

        Map<String, Object> tenantClassifier2 = new HashMap<>();
        tenantClassifier2.put("tenantId", "12345");
        tenantClassifier2.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier2.put("scope", "tenant");
        tenantClassifier2.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);

        applyDeclarativeDBConfig(null, "tenant", "new");

        DatabaseResponse activeTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase1, "tenant1", "tenant1");

        DatabaseResponse activeTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase2, "tenant2", "tenant2");

        doWarmup();

        tenantClassifier1.put("namespace", TEST_NAMESPACE_CANDIDATE);

        DatabaseResponse candidateTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertNotEquals(activeTenantDatabase1.getName(), candidateTenantDatabase1.getName());
        Assertions.assertNotEquals(activeTenantDatabase1.getClassifier(), candidateTenantDatabase1.getClassifier());

        checkDatabaseDoesNotHaveValue(candidateTenantDatabase1, "tenant1");
        checkDatabaseDoesNotHaveValue(candidateTenantDatabase1, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase1, "test1", "test1");
        checkDatabaseDoesNotHaveValue(activeTenantDatabase1, "test1");

        tenantClassifier2.put("namespace", TEST_NAMESPACE_CANDIDATE);
        DatabaseResponse candidateTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertNotEquals(activeTenantDatabase2.getName(), candidateTenantDatabase2.getName());
        Assertions.assertNotEquals(activeTenantDatabase2.getClassifier(), candidateTenantDatabase2.getClassifier());

        checkDatabaseDoesNotHaveValue(candidateTenantDatabase2, "tenant1");
        checkDatabaseDoesNotHaveValue(candidateTenantDatabase2, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, "test2", "test2");
        checkDatabaseDoesNotHaveValue(activeTenantDatabase2, "test2");
    }

    @Test
    @Tag("backup")
    void testWarmupDeclarativeTenantDatabaseClone() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }
        Map<String, Object> tenantClassifier1 = new HashMap<>();
        tenantClassifier1.put("tenantId", "1234");
        tenantClassifier1.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier1.put("scope", "tenant");
        tenantClassifier1.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);

        Map<String, Object> tenantClassifier2 = new HashMap<>();
        tenantClassifier2.put("tenantId", "12345");
        tenantClassifier2.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier2.put("scope", "tenant");
        tenantClassifier2.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);

        applyDeclarativeDBConfig(null, "tenant", "clone");

        DatabaseResponse activeTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase1, "tenant1", "tenant1");

        DatabaseResponse activeTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase2, "tenant2", "tenant2");

        doWarmup();

        tenantClassifier1.put("namespace", TEST_NAMESPACE_CANDIDATE);

        DatabaseResponse candidateTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertNotEquals(activeTenantDatabase1.getName(), candidateTenantDatabase1.getName());
        Assertions.assertNotEquals(activeTenantDatabase1.getClassifier(), candidateTenantDatabase1.getClassifier());

        helperV3.checkConnectionPostgres(candidateTenantDatabase1, null, "tenant1");
        checkDatabaseDoesNotHaveValue(candidateTenantDatabase1, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase1, "test1", "test1");
        checkDatabaseDoesNotHaveValue(activeTenantDatabase1, "test1");

        tenantClassifier2.put("namespace", TEST_NAMESPACE_CANDIDATE);
        DatabaseResponse candidateTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertNotEquals(activeTenantDatabase2.getName(), candidateTenantDatabase2.getName());
        Assertions.assertNotEquals(activeTenantDatabase2.getClassifier(), candidateTenantDatabase2.getClassifier());

        checkDatabaseDoesNotHaveValue(candidateTenantDatabase2, "tenant1");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, null, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, "test2", "test2");
        checkDatabaseDoesNotHaveValue(activeTenantDatabase2, "test2");
    }

    @Test
    void testWarmupDeclarativeAbsentTenantDatabaseClone() throws IOException, SQLException {
        String MS2 = TEST_MICROSERVICE_NAME + "2";

        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        applyDeclarativeDBConfig(null, "tenant", "clone");

        Map<String, Object> tenantClassifier1 = new HashMap<>();
        tenantClassifier1.put("tenantId", "1234");
        tenantClassifier1.put("microserviceName", MS2);
        tenantClassifier1.put("scope", "tenant");
        tenantClassifier1.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(MS2)
                        .build(), 201);
        DatabaseResponse activeTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(MS2)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase1, "tenant1", "tenant1");

        doWarmup();

        Map<String, Object> tenantClassifier2 = new HashMap<>(tenantClassifier1);
        tenantClassifier2.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier2.put("namespace", TEST_NAMESPACE_CANDIDATE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);
    }

    @Test
    @Tag("backup")
    void testWarmupTenantDatabaseCreationAfterConfigClone() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }
        Map<String, Object> tenantClassifier1 = new HashMap<>();
        tenantClassifier1.put("tenantId", "1234");
        tenantClassifier1.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier1.put("scope", "tenant");
        tenantClassifier1.put("namespace", TEST_NAMESPACE_ACTIVE);


        Map<String, Object> tenantClassifier2 = new HashMap<>();
        tenantClassifier2.put("tenantId", "12345");
        tenantClassifier2.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier2.put("scope", "tenant");
        tenantClassifier2.put("namespace", TEST_NAMESPACE_ACTIVE);

        applyDeclarativeDBConfig(null, "tenant", "clone");

        DatabaseResponse activeTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);
        helperV3.checkConnectionPostgres(activeTenantDatabase1, "tenant1", "tenant1");

        DatabaseResponse activeTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);
        helperV3.checkConnectionPostgres(activeTenantDatabase2, "tenant2", "tenant2");

        doWarmup();

        tenantClassifier1.put("namespace", TEST_NAMESPACE_CANDIDATE);

        DatabaseResponse candidateTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertNotEquals(activeTenantDatabase1.getName(), candidateTenantDatabase1.getName());
        Assertions.assertNotEquals(activeTenantDatabase1.getClassifier(), candidateTenantDatabase1.getClassifier());

        helperV3.checkConnectionPostgres(candidateTenantDatabase1, null, "tenant1");
        checkDatabaseDoesNotHaveValue(candidateTenantDatabase1, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase1, "test1", "test1");
        checkDatabaseDoesNotHaveValue(activeTenantDatabase1, "test1");

        tenantClassifier2.put("namespace", TEST_NAMESPACE_CANDIDATE);
        DatabaseResponse candidateTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertNotEquals(activeTenantDatabase2.getName(), candidateTenantDatabase2.getName());
        Assertions.assertNotEquals(activeTenantDatabase2.getClassifier(), candidateTenantDatabase2.getClassifier());

        checkDatabaseDoesNotHaveValue(candidateTenantDatabase2, "tenant1");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, null, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, "test2", "test2");
        checkDatabaseDoesNotHaveValue(activeTenantDatabase2, "test2");
    }

    @Test
    void testWarmupDeclarativeTenantDatabase() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }
        Map<String, Object> tenantClassifier1 = new HashMap<>();
        tenantClassifier1.put("tenantId", "1234");
        tenantClassifier1.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier1.put("scope", "tenant");
        tenantClassifier1.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);

        Map<String, Object> tenantClassifier2 = new HashMap<>();
        tenantClassifier2.put("tenantId", "12345");
        tenantClassifier2.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier2.put("scope", "tenant");
        tenantClassifier2.put("namespace", TEST_NAMESPACE_ACTIVE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);


        DatabaseResponse activeTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase1, "tenant1", "tenant1");

        DatabaseResponse activeTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        helperV3.checkConnectionPostgres(activeTenantDatabase2, "tenant2", "tenant2");

        doWarmup();

        tenantClassifier1.put("namespace", TEST_NAMESPACE_CANDIDATE);

        DatabaseResponse candidateTenantDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier1, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertEquals(activeTenantDatabase1.getName(), candidateTenantDatabase1.getName());
        Assertions.assertNotEquals(activeTenantDatabase1.getClassifier(), candidateTenantDatabase1.getClassifier());

        helperV3.checkConnectionPostgres(candidateTenantDatabase1, null, "tenant1");
        checkDatabaseDoesNotHaveValue(candidateTenantDatabase1, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase1, "test1", "test1");
        helperV3.checkConnectionPostgres(activeTenantDatabase1, null, "test1");

        tenantClassifier2.put("namespace", TEST_NAMESPACE_CANDIDATE);
        DatabaseResponse candidateTenantDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(tenantClassifier2, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        Assertions.assertEquals(activeTenantDatabase2.getName(), candidateTenantDatabase2.getName());
        Assertions.assertNotEquals(activeTenantDatabase2.getClassifier(), candidateTenantDatabase2.getClassifier());

        checkDatabaseDoesNotHaveValue(candidateTenantDatabase2, "tenant1");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, null, "tenant2");
        helperV3.checkConnectionPostgres(candidateTenantDatabase2, "test2", "test2");
        helperV3.checkConnectionPostgres(activeTenantDatabase2, null, "test2");
    }

    @Test
    void testCreateInActiveThenInCandidate() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 201);
        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());
    }

    @Test
    void testCreateInCandidateThenInActive() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 201);
        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 200);

        Assertions.assertEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());
    }

    @Test
    void testCreateInLegacyThenInActive() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        try (Response response = bgHelper.promoteDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            assertTrue(response.isSuccessful());
        }
        checkBgDomainStatus(LEGACY_STATE, ACTIVE_STATE);

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 201);
        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 201);

        Assertions.assertNotEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());
    }

    private static void checkDatabaseDoesNotHaveValue(DatabaseResponse candidateTenantDatabase1, String checkData) throws SQLException, IOException {
        boolean isServiceChanged = true;
        try {
            helperV3.checkConnectionPostgres(candidateTenantDatabase1, null, checkData);
        } catch (AssertionError assertionError) {
            log.info("expected exception");
            isServiceChanged = false;
        }
        assertFalse(isServiceChanged);
    }


    @Test
    @Tag("Smoke")
    void testCommitDomain() throws IOException {
        DatabaseResponse activeDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_ACTIVE), 201);
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        doWarmup();

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("test", TEST_NAMESPACE_CANDIDATE), 200);

        Assertions.assertEquals(activeDatabase.getName(), candidateDatabase.getName());
        Assertions.assertNotEquals(activeDatabase.getClassifier(), candidateDatabase.getClassifier());

        try (Response warmupResponse = bgHelper.commitDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, warmupResponse.code());
        }

        List<DatabaseV3> candidateNamespaces = notMarkedForDrop(helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE));
        Assertions.assertEquals(0, candidateNamespaces.stream().filter(db -> !db.getName().equals(candidateDatabase.getName())).count());
    }

    @Test
    void testDatabaseDeletionDuringCommit() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        applyDeclarativeDBConfig("toNew", "service", "new");

        DatabaseResponse sharedDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("SHARED", TEST_NAMESPACE_ACTIVE), 201);

        doWarmup();

        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("CANDIDATE", TEST_NAMESPACE_CANDIDATE), 201);

        List<DatabaseV3> activeDatabases = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_ACTIVE);
        Assertions.assertEquals(2, activeDatabases.size());

        List<DatabaseV3> candidateDatabases = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
        Assertions.assertEquals(3, candidateDatabases.size());

        try (Response commitResponse = bgHelper.commitDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, commitResponse.code());
        }
        // wait for all required databases to get deleted
        Failsafe.with(DEFAULT_RETRY_POLICY.copy().withDelay(Duration.ofSeconds(1))).run(() -> {
            List<DatabaseV3> databases = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_CANDIDATE);
            assertEquals(1, databases.size(), "Namespace still have the following databases: " +
                    databases.stream().map(DatabaseV3::getName).collect(Collectors.joining(",")));
            assertEquals(sharedDatabase.getName(), databases.getFirst().getName());
        });

        List<DatabaseV3> activeNamespaceAfterCommit = helperV3.getDatabasesByNamespace(TEST_NAMESPACE_ACTIVE);
        Assertions.assertEquals(2, activeNamespaceAfterCommit.size());
    }

    @Test
    @Tag("backup")
    void testDeclarativeCloneDatabaseRequestInCandidateNamespace() throws IOException, SQLException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        Map<String, Object> versioningClassifier = new ClassifierBuilder().test("declarativeToClone").declarative().build();
        DeclarativePayload versioningDbDeclarationPayload = new DeclarativeDBConfigBuilder()
                .classifier(versioningClassifier)
                .versioning("new")
                .build().asPayload(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(versioningDbDeclarationPayload);

        DatabaseCreateRequestV3 simplePostgresCreateRequest = getSimplePostgresCreateRequest("declarativeToClone", TEST_NAMESPACE_ACTIVE);
        DatabaseResponse activeVersionedDb = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                simplePostgresCreateRequest, 200);
        helperV3.checkConnectionPostgres(activeVersionedDb, "active", "active");

        doWarmup();

        versioningClassifier.put("namespace", TEST_NAMESPACE_CANDIDATE);
        DatabaseResponse candidateVersionedDb = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(versioningClassifier, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        checkDatabaseDoesNotHaveValue(candidateVersionedDb, "active");
        helperV3.checkConnectionPostgres(candidateVersionedDb, "candidate", "candidate");

        Map<String, Object> transactionalClassifier = new ClassifierBuilder().test("transactional").declarative().build();
        DeclarativePayload transactionalDbDeclarationPayload = new DeclarativeDBConfigBuilder()
                .classifier(transactionalClassifier)
                .initClone(versioningClassifier)
                .build().asPayload(TEST_NAMESPACE_CANDIDATE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(transactionalDbDeclarationPayload);

        transactionalClassifier.put("namespace", TEST_NAMESPACE_CANDIDATE);
        DatabaseResponse transactionalDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                DatabaseCreateRequestV3.builder(transactionalClassifier, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 200);
        checkDatabaseDoesNotHaveValue(transactionalDatabase, "active");
        helperV3.checkConnectionPostgres(transactionalDatabase, null, "candidate");
        helperV3.checkConnectionPostgres(transactionalDatabase, "test", "test");
        checkDatabaseDoesNotHaveValue(activeVersionedDb, "test");
        checkDatabaseDoesNotHaveValue(candidateVersionedDb, "test");
    }


    @Test
    void testWarmupCloneRolesAndRules() throws IOException {
        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        List<DeclarativePayload> declarations = new ArrayList<>();
        declarations.add(new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("forCleaning"))
                .build().asPayload(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME));
        declarations.add(new DeclarativeRoleConfigBuilder()
                .disableGlobalPermissions()
                .build().asPayload(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME));
        declarativeHelper.applyDeclarativeConfigs(declarations);

        Map.Entry<String, String> label = balancingRulesHelperV3.getUniqLabelsByDbType(POSTGRES_TYPE);
        String microserviceRuleLabel = label.getKey() + "=" + label.getValue();
        BalancingRulesV3IT.createPerMicroserviceRule(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME, microserviceRuleLabel, 201);

        BalancingRulesV3IT.createPerNamespaceRule(TEST_NAMESPACE_ACTIVE, 201);

        AccessGrantsResponse accessGrantsResponseActive = helperV3.getAccessRoles(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME, 200);
        assertEquals(true, accessGrantsResponseActive.getDisableGlobalPermissions());
        helperV3.getAccessRoles(TEST_NAMESPACE_CANDIDATE, TEST_MICROSERVICE_NAME, 404);

        doWarmup();

        BalancingRulesV3IT.createPerNamespaceRule(TEST_NAMESPACE_CANDIDATE, 200);
        AccessGrantsResponse accessGrantsResponseCandidate = helperV3.getAccessRoles(TEST_NAMESPACE_CANDIDATE, TEST_MICROSERVICE_NAME, 200);
        assertNull(accessGrantsResponseCandidate.getDisableGlobalPermissions());
    }

    @Test
    @Tag("backup")
    void testDeleteBackupAfterWarmup() throws IOException, SQLException {
        BackupHelperV3 backupsHelper = new BackupHelperV3(dbaasServiceUrl, helperV3);

        try (Response initResponse = bgHelper.initDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(200, initResponse.code());
        }

        applyDeclarativeDBConfig("db1", "service", "clone");
        applyDeclarativeDBConfig("db2", "service", "clone");

        DatabaseResponse activeDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("db1", TEST_NAMESPACE_ACTIVE), 200);
        DatabaseResponse activeDatabase2 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_ACTIVE),
                getSimplePostgresCreateRequest("db2", TEST_NAMESPACE_ACTIVE), 200);
        helperV3.checkConnectionPostgres(activeDatabase1, "toClone", "toClone");
        helperV3.checkConnectionPostgres(activeDatabase2, "toClone", "toClone");

        String trackingId = doWarmup();
        List<UUID> backups = new ArrayList<>();
        try (Response operationStatus = bgHelper.getOperationStatus(trackingId)) {
            JsonNode node = objectMapper.readTree(operationStatus.body().string());
            node.get("operationDetails").forEach(jsonNode -> backups.add(UUID.fromString(jsonNode.get("backupId").asText())));
        }
        assertEquals(2, backups.size());
        for (UUID backupId : backups) {
            NamespaceBackupV3 backup = new NamespaceBackupV3();
            backup.setNamespace(TEST_NAMESPACE_ACTIVE);
            backup.setId(backupId);
            backupsHelper.assertBackupNotExist(helperV3.getBackupDaemonAuthorization(), backup);
        }
    }

    private DatabaseCreateRequestV3 getSimplePostgresCreateRequest(String testClassifierValue, String namespace) {
        return getPrimaryCreateDbRequestBuilder(POSTGRES_TYPE, testClassifierValue, namespace)
                .originService(TEST_MICROSERVICE_NAME)
                .build();
    }

    private DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder getPrimaryCreateDbRequestBuilder(String type, String testClassifierValue, String namespace) {
        return DatabaseCreateRequestV3.builder(new ClassifierBuilder().test(testClassifierValue).ns(namespace).build(), type);
    }

    private void applyDeclarativeDBConfig(String testValue, String scope, String approach) throws IOException {
        DeclarativePayload payload = new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test(testValue).scope(scope))
                .versioning(approach)
                .build().asPayload(TEST_NAMESPACE_ACTIVE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(payload);
    }

    private String doWarmup() throws IOException {
        String trackingId;
        try (Response warmupResponse = bgHelper.warmupDomain(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)) {
            Assertions.assertEquals(202, warmupResponse.code());
            JsonNode node = objectMapper.readTree(warmupResponse.body().string());
            trackingId = node.get("trackingId").asText();
        }
        Failsafe.with(OPERATION_STATUS_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(3))).run(() -> {
            try (Response operationStatus = bgHelper.getOperationStatus(trackingId)) {
                JsonNode node = objectMapper.readTree(operationStatus.body().string());
                assertFalse(node.get("status").asText().equals("in progress") || node.get("status").asText().equals("not started"));
            }
        });
        return trackingId;
    }

    private Response getDomain(String namespace) throws IOException {
        Request request = helperV3.createRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/get-domains/" + namespace, helperV3.getClusterDbaAuthorization(), null, "GET");

        return okHttpClient.newCall(request).execute();
    }

    private void createBgDomain(String originName, String peerName, String controllerName) throws IOException {
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespaceActive = new BgStateRequest.BGStateNamespace();
        bgNamespaceActive.setState(ACTIVE_STATE);
        bgNamespaceActive.setName(originName);
        bgNamespaceActive.setVersion("v1");
        bgState.setOriginNamespace(bgNamespaceActive);
        BgStateRequest.BGStateNamespace bgNamespaceIdle = new BgStateRequest.BGStateNamespace();
        bgNamespaceIdle.setState(IDLE_STATE);
        bgNamespaceIdle.setName(peerName);
        bgNamespaceIdle.setVersion("v2");
        bgState.setPeerNamespace(bgNamespaceIdle);
        bgState.setControllerNamespace(controllerName);
        bgStateRequest.setBGState(bgState);
        try (Response initResponse = bgHelper.initDomain(bgStateRequest)) {
            Assertions.assertEquals(200, initResponse.code());
        }
    }
}
