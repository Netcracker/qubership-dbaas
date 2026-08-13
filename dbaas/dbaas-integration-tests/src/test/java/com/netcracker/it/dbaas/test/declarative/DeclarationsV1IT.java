package com.netcracker.it.dbaas.test.declarative;

import com.fasterxml.jackson.databind.JsonNode;
import com.netcracker.it.dbaas.entity.DatabaseCreateRequestV3;
import com.netcracker.it.dbaas.entity.DatabaseResponse;
import com.netcracker.it.dbaas.entity.Role;
import com.netcracker.it.dbaas.entity.config.DeclarativePayload;
import com.netcracker.it.dbaas.entity.config.ServiceRole;
import com.netcracker.it.dbaas.entity.response.AccessGrantsResponse;
import com.netcracker.it.dbaas.helpers.ClassifierBuilder;
import com.netcracker.it.dbaas.helpers.DeclarativeConfigHelper;
import com.netcracker.it.dbaas.test.AbstractIT;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.Response;
import org.junit.jupiter.api.*;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.netcracker.it.dbaas.entity.config.DatabaseDeclaration.DeclarativeDBConfigBuilder;
import static com.netcracker.it.dbaas.entity.config.RolesRegistration.DeclarativeRoleConfigBuilder;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.*;
import static com.netcracker.it.dbaas.helpers.DeclarativeConfigHelper.APPLY_CONFIGS_RETRY_POLICY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Slf4j
class DeclarationsV1IT extends AbstractIT {
    private static DeclarativeConfigHelper declarativeHelper;

    @BeforeAll
    public static void setUp() {
        declarativeHelper = new DeclarativeConfigHelper(helperV3);
    }

    @BeforeEach
    public void initAndCleanDbs() throws IOException {
        cleanDbs();
    }

    @AfterEach
    public void cleanDbs() throws IOException {
        log.info("Clean databases");
        helperV3.deleteDatabases(helperV3.getClusterDbaAuthorization(), TEST_NAMESPACE);
    }

    @Test
    @Tag("backup")
    void testApplyConfig() throws IOException, SQLException {

        DatabaseResponse serviceDatabase1 = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                getSimplePostgresCreateRequest("toClone", "service3", TEST_NAMESPACE), 201);

        helperV3.checkConnectionPostgres(serviceDatabase1, "toClone", "toClone");

        Map<String, Object> tenantClassifier = new HashMap<>();
        tenantClassifier.put("tenantId", "1234");
        tenantClassifier.put("microserviceName", TEST_MICROSERVICE_NAME);
        tenantClassifier.put("scope", "tenant");
        tenantClassifier.put("namespace", TEST_NAMESPACE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                DatabaseCreateRequestV3.builder(tenantClassifier, POSTGRES_TYPE).originService(TEST_MICROSERVICE_NAME)
                        .build(), 201);

        List<DeclarativePayload> declarations = new ArrayList<>();
        declarations.add(new DeclarativeRoleConfigBuilder()
                .service("service1", Role.ADMIN.getRoleValue())
                .build().asPayload(TEST_NAMESPACE, "service1"));

        declarations.add(new DeclarativeRoleConfigBuilder()
                .service("service3", Role.ADMIN.getRoleValue())
                .build().asPayload(TEST_NAMESPACE, "service2"));

        declarations.add(new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("toClone").ms("service3"))
                .build().asPayload(TEST_NAMESPACE, "service3"));
        declarations.add(new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("tenantNew").ms("service3").tenant())
                .build().asPayload(TEST_NAMESPACE, "service3"));
        declarations.add(new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("clone3").ms("service3"))
                .initClone(new ClassifierBuilder().test("toClone").ms("service3"))
                .build().asPayload(TEST_NAMESPACE, "service3"));

        declarativeHelper.applyDeclarativeConfigs(declarations);


        DatabaseResponse copiedService = helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                getSimplePostgresCreateRequest("clone3", "service3", TEST_NAMESPACE), 200);
        helperV3.checkConnectionPostgres(copiedService, null, "toClone");
        helperV3.checkConnectionPostgres(copiedService, "cloned", "cloned");
        boolean isService1Changed = true;
        try {
            helperV3.checkConnectionPostgres(serviceDatabase1, null, "cloned");
        } catch (AssertionError assertionError) {
            log.info("expected exception");
            isService1Changed = false;
        }
        Assertions.assertFalse(isService1Changed);


        tenantClassifier = new HashMap<>();
        tenantClassifier.put("tenantId", "1234");
        tenantClassifier.put("dbaas_auto_test", "tenantNew");
        tenantClassifier.put("microserviceName", "service3");
        tenantClassifier.put("scope", "tenant");
        tenantClassifier.put("namespace", TEST_NAMESPACE);
        helperV3.createDatabase(String.format(DATABASES_V3, TEST_NAMESPACE),
                DatabaseCreateRequestV3.builder(tenantClassifier, POSTGRES_TYPE).originService("service3")
                        .build(), 200);

        AccessGrantsResponse accessGrantsResponse1 = helperV3.getAccessRoles(TEST_NAMESPACE, "service1", 200);
        assertFalse(accessGrantsResponse1.getServices().isEmpty());
        ServiceRole service1Role = accessGrantsResponse1.getServices().getFirst();
        assertEquals("service1", service1Role.getName());
        assertEquals(Role.ADMIN.getRoleValue(), service1Role.getRoles().getFirst());

        AccessGrantsResponse accessGrantsResponse2 = helperV3.getAccessRoles(TEST_NAMESPACE, "service2", 200);
        assertFalse(accessGrantsResponse2.getServices().isEmpty());
        ServiceRole service2Role = accessGrantsResponse2.getServices().getFirst();
        assertEquals("service3", service2Role.getName());
        assertEquals(Role.ADMIN.getRoleValue(), service2Role.getRoles().getFirst());
    }

    @Test
    @Tag("backup")
    void testApplyConfigWithWaitingForResources() throws IOException {
        DeclarativePayload waitingDeclarativePayload = new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("clone3").ms("service3"))
                .initClone(new ClassifierBuilder().test("toClone").ms("service3"))
                .build().asPayload(TEST_NAMESPACE, "service3");

        String waitingTrackingId;
        try (Response response = helperV3.sendRequest(CONFIGS_PATH_V1 + "/apply", waitingDeclarativePayload, "POST")) {
            assertEquals(202, response.code());
            JsonNode node = helperV3.objectMapper.readTree(response.body().string());
            waitingTrackingId = node.get("trackingId").asText();
        }
        Assertions.assertThrows(AssertionFailedError.class, () ->
                Failsafe.with(new RetryPolicy<>().withDelay(Duration.ofSeconds(1)).withMaxRetries(10)).run(() -> {
                    try (Response applyConfigStatus = declarativeHelper.getApplyConfigStatus(waitingTrackingId)) {
                        String body = applyConfigStatus.body().string();
                        log.info("Status response: {}", body);
                        assertEquals(200, applyConfigStatus.code());
                        JsonNode node = helperV3.objectMapper.readTree(body);
                        assertEquals("COMPLETED", node.get("status").asText());
                    }
                }));

        DeclarativePayload dependencyDeclarativePayload = new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("toClone").ms("service3"))
                .build().asPayload(TEST_NAMESPACE, "service3");
        declarativeHelper.applyDeclarativeConfig(dependencyDeclarativePayload);

        Failsafe.with(APPLY_CONFIGS_RETRY_POLICY).run(() -> {
            try (Response applyConfigStatus = declarativeHelper.getApplyConfigStatus(waitingTrackingId)) {
                String body = applyConfigStatus.body().string();
                log.info("Status response: {}", body);
                assertEquals(200, applyConfigStatus.code());
                JsonNode node = helperV3.objectMapper.readTree(body);
                assertEquals("COMPLETED", node.get("status").asText());
            }
        });
    }

    @Test
    void testApplyConfigFail() throws IOException {
        DeclarativePayload waitingDeclarativePayload = new DeclarativeDBConfigBuilder()
                .classifier(new ClassifierBuilder().test("new").ms(TEST_MICROSERVICE_NAME))
                .type("not_existing_type")
                .initNew()
                .build().asPayload(TEST_NAMESPACE, TEST_MICROSERVICE_NAME);

        String waitingTrackingId;
        try (Response response = helperV3.sendRequest(CONFIGS_PATH_V1 + "/apply", waitingDeclarativePayload, "POST")) {
            assertEquals(202, response.code());
            JsonNode node = helperV3.objectMapper.readTree(response.body().string());
            waitingTrackingId = node.get("trackingId").asText();
        }
        Failsafe.with(APPLY_CONFIGS_RETRY_POLICY).run(() -> {
            try (Response applyConfigStatus = declarativeHelper.getApplyConfigStatus(waitingTrackingId)) {
                String body = applyConfigStatus.body().string();
                log.info("Status response: {}", body);
                assertEquals(200, applyConfigStatus.code());
                JsonNode node = helperV3.objectMapper.readTree(body);
                assertEquals("FAILED", node.get("status").asText());
            }
        });
    }

    @Test
    @Tag("Smoke")
    void testDisableGlobalPermissionsOnly() throws IOException {
        DeclarativePayload payload = new DeclarativeRoleConfigBuilder()
                .disableGlobalPermissions()
                .build().asPayload(TEST_NAMESPACE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(payload);

        AccessGrantsResponse accessGrantsResponse = helperV3.getAccessRoles(TEST_NAMESPACE, TEST_MICROSERVICE_NAME, 200);
        assertEquals(true, accessGrantsResponse.getDisableGlobalPermissions());

        payload = new DeclarativeRoleConfigBuilder().build().asPayload(TEST_NAMESPACE, TEST_MICROSERVICE_NAME);
        declarativeHelper.applyDeclarativeConfig(payload);

        accessGrantsResponse = helperV3.getAccessRoles(TEST_NAMESPACE, TEST_MICROSERVICE_NAME, 200);
        assertEquals(false, accessGrantsResponse.getDisableGlobalPermissions());
    }

    private DatabaseCreateRequestV3 getSimplePostgresCreateRequest(String testClassifierValue, String microserviceName, String namespace) {
        return getPrimaryCreateDbRequestBuilder(POSTGRES_TYPE, testClassifierValue, microserviceName, namespace)
                .originService(microserviceName)
                .build();
    }

    private DatabaseCreateRequestV3.DatabaseCreateRequestV3Builder getPrimaryCreateDbRequestBuilder(String type,
                                                                                                    String testClassifierValue,
                                                                                                    String microserviceName,
                                                                                                    String namespace) {
        return DatabaseCreateRequestV3.builder(new ClassifierBuilder().test(testClassifierValue).ms(microserviceName).ns(namespace).build(), type);
    }
}
