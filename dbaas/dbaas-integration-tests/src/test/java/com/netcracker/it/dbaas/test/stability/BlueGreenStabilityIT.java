package com.netcracker.it.dbaas.test.stability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.junit.cloudcore.extension.service.PodPortForwardParams;
import com.netcracker.it.dbaas.entity.BgNamespaceRequest;
import com.netcracker.it.dbaas.entity.DatabaseCreateRequestV3;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.config.DeclarativePayload;
import com.netcracker.it.dbaas.helpers.BGHelper;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DeclarativeConfigHelper;
import com.netcracker.it.dbaas.test.AbstractIT;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.Response;
import org.junit.jupiter.api.*;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.netcracker.it.dbaas.entity.config.DatabaseDeclaration.DeclarativeDBConfigBuilder;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.DATABASES_V3;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_MICROSERVICE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
@Tag("failover")
class BlueGreenStabilityIT extends AbstractIT {

    protected final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(5));
    private final static String TEST_NAMESPACE_ACTIVE = "active-test-namespace";

    private final static String TEST_NAMESPACE_CANDIDATE = "candidate-test-namespace";
    private final static Duration SCALE_WAIT_TIME_MINUTES = Duration.ofMinutes(3);


    private static BGHelper bgHelper;
    private static DeclarativeConfigHelper declarativeHelper;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void setUp() {
        bgHelper = new BGHelper(helperV3);
        declarativeHelper = new DeclarativeConfigHelper(helperV3);
    }

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        Function<String, String> resolve = name -> Optional.ofNullable(System.getProperty(name)).orElseGet(() -> System.getenv(name));
        boolean bgFailoverTest = Boolean.parseBoolean(resolve.apply("BG_FAILOVER_TEST"));
        assumeTrue(bgFailoverTest, "BG_FAILOVER_TEST must be enabled");
        cleanDbs();
    }

    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE_ACTIVE);
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE_CANDIDATE);
        bgHelper.destroyDomain(new BgNamespaceRequest(TEST_NAMESPACE_ACTIVE, TEST_NAMESPACE_CANDIDATE)).close();
    }

    @SneakyThrows
    @Test
    void testDeletePod() {
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
        Deployment deployment = getDeployment(kubernetesClient);
        int requiredPodsCount = deployment.getStatus().getReplicas();

        deletePod(kubernetesClient.getNamespace(), "dbaas-aggregator");

        Failsafe.with(DEFAULT_RETRY_POLICY.copy().withMaxDuration(SCALE_WAIT_TIME_MINUTES)).run(() -> {
            DeploymentStatus status = getDeployment(kubernetesClient).getStatus();
            log.info(status.toString());
            assertEquals(requiredPodsCount, (int) Optional.ofNullable(status.getReplicas()).orElse(0));
            assertEquals(requiredPodsCount, (int) Optional.ofNullable(status.getReadyReplicas()).orElse(0));
            List<Pod> pods = kubernetesClient.pods().withLabel("name", DBAAS_SERVICE_NAME).list().getItems();
            log.info("Pods count: {}", pods.size());
            assertEquals(requiredPodsCount, pods.size());
        });
        Thread.sleep(5000);
        List<Pod> pods = kubernetesClient.pods().withLabel("name", DBAAS_SERVICE_NAME).list().getItems();
        portForwardService.closePortForwards();
        portForwardService.portForward(PodPortForwardParams.builder(pods.getFirst().getMetadata().getName(), HTTP_PORT).build());

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(3))).run(() -> {
            try (Response operationStatus = bgHelper.getOperationStatus(finalTrackingId)) {
                JsonNode node = objectMapper.readTree(operationStatus.body().string());
                assertFalse(node.get("status").asText().equals("in progress") || node.get("status").asText().equals("not started"));
            }
        });

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);
        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
        cleanDbs();

    }

    @Test
    void testDeleteAdapterPod() throws IOException, SQLException {
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
        deleteAdapterPod("core-postgresql", "nc-dbaas-postgres-adapter");

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(3))).run(() -> {
            try (Response operationStatus = bgHelper.getOperationStatus(finalTrackingId)) {
                JsonNode node = objectMapper.readTree(operationStatus.body().string());
                assertFalse(node.get("status").asText().equals("in progress") || node.get("status").asText().equals("not started"));
            }
        });

        DatabaseResponse candidateDatabase = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE_CANDIDATE),
                getSimplePostgresCreateRequest("toClone", TEST_NAMESPACE_CANDIDATE), 200);
        helperV3.checkConnectionPostgres(candidateDatabase, null, "toClone");
        cleanDbs();
    }

    @Test
    void testDeleteBackupPod() throws IOException, SQLException {
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
        deleteAdapterPod("core-postgresql", "postgres-backup-daemon");

        Assertions.assertThrows(AssertionFailedError.class, () ->
                Failsafe.with(OPERATION_STATUS_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(3))).run(() -> {
                    try (Response operationStatus = bgHelper.getOperationStatus(finalTrackingId)) {
                        JsonNode node = objectMapper.readTree(operationStatus.body().string());
                        assertFalse(node.get("status").asText().equals("in progress") || node.get("status").asText().equals("not started"));
                    }
                })
        );
        cleanDbs();
    }

    @SneakyThrows
    @Test
    void testDeletePatroniPod() {
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
        deletePatroniPod("core-postgresql", "patroni");

        Failsafe.with(OPERATION_STATUS_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(3))).run(() -> {
            try (Response operationStatus = bgHelper.getOperationStatus(finalTrackingId)) {
                Assertions.assertEquals(500, operationStatus.code());
            }
        });
        Thread.sleep(30000);
    }


    private DatabaseCreateRequestV3 getSimplePostgresCreateRequest(String testClassifierValue, String namespace) {
        return getPrimaryCreateDbRequestBuilder(POSTGRES_TYPE, testClassifierValue, namespace)
                .originService(TEST_MICROSERVICE_NAME)
                .build();
    }

    private DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder getPrimaryCreateDbRequestBuilder(String type, String testClassifierValue, String namespace) {
        return DatabaseCreateRequestV3.builder(new ClassifierBuilder().test(testClassifierValue).ns(namespace).build(), type);
    }

    public void deletePod(String namespace, String serviceName) {
        kubernetesClient.pods().inNamespace(namespace).withLabel("name", serviceName).delete();
    }


    public void deleteAdapterPod(String namespace, String serviceName) {
        kubernetesClient.pods().inNamespace(namespace).withLabel("app", serviceName).delete();
    }

    public void deletePatroniPod(String namespace, String serviceName) {
        Map<String, String> labels = new HashMap<>();
        labels.put("app", serviceName);
        labels.put("pgtype", "master");
        kubernetesClient.pods().inNamespace(namespace).withLabels(labels).delete();
    }

    private Deployment getDeployment(KubernetesClient kubernetesClient) {
        return kubernetesClient.apps().deployments().withName(AbstractIT.DBAAS_SERVICE_NAME).get();
    }
}
