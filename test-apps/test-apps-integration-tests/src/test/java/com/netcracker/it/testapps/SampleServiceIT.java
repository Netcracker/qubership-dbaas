package com.netcracker.it.testapps;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.netcracker.it.testapps.helpers.ClusterHelper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.testapps.helpers.ClusterHelper.execute;
import static com.netcracker.it.testapps.helpers.ClusterHelper.executeEventually;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SampleServiceIT {

    private static final String CONNECTION_PROPERTIES_KEY = "connectionProperties.json";
    private static final String METADATA_KEY = "metadata.json";
    private static final String POSTGRES_TYPE = "postgresql";
    private static final String TENANT_ID = "acme";
    private static final String ADMIN_ROLE = "admin";
    private static final int HTTP_PORT = 8080;
    private static final MediaType JSON = MediaType.get("application/json");

    private static final Set<String> SUCCESSFUL_SECRET_READY_REASONS =
            Set.of("SecretCreated", "SecretRotated", "SecretUpToDate");

    private static final Set<String> DATABASE_PROVISIONED_REASONS = Set.of("DatabaseProvisioned");

    private static final CustomResourceDefinitionContext CRD_INTERNAL_DATABASE =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("dbaas.netcracker.com")
                    .withVersion("v1")
                    .withPlural("internaldatabases")
                    .withScope("Namespaced")
                    .build();

    private static final CustomResourceDefinitionContext CRD_DATABASE_SECRET_CLAIM =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("dbaas.netcracker.com")
                    .withVersion("v1")
                    .withPlural("databasesecretclaims")
                    .withScope("Namespaced")
                    .build();

    private static final Gson GSON = new Gson();
    private static final KubernetesClient kubernetesClient = ClusterHelper.cluster();

    private String namespace;
    private String service;
    private LocalPortForward sampleService;

    protected abstract String serviceName();

    @BeforeAll
    void setUpSampleService() {
        namespace = ClusterHelper.namespace();
        service = serviceName();
        String internalDatabase = service + "-postgres";
        String internalDatabaseTenant = internalDatabase + "-tenant";

        waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabase, "Succeeded", "True", DATABASE_PROVISIONED_REASONS, "False");
        waitForDesiredState(CRD_INTERNAL_DATABASE, internalDatabaseTenant, "Succeeded", "True", DATABASE_PROVISIONED_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, internalDatabase + "-claim", "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, internalDatabase + "-admin-claim", "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, internalDatabaseTenant + "-claim", "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, internalDatabaseTenant + "-admin-claim", "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");

        assertSecret(waitForSecret(internalDatabase), "service", null, null);
        assertSecret(waitForSecret(internalDatabase + "-admin"), "service", null, ADMIN_ROLE);
        assertSecret(waitForSecret(internalDatabaseTenant), "tenant", TENANT_ID, null);
        assertSecret(waitForSecret(internalDatabaseTenant + "-admin"), "tenant", TENANT_ID, ADMIN_ROLE);

        sampleService = ClusterHelper.toReadyPod(service, HTTP_PORT);
        ClusterHelper.awaitHealthy(url("/health"), service);
    }

    @AfterAll
    void closeResources() throws IOException {
        sampleService.close();
    }

    @Test
    void serviceNoRole_Q1() throws IOException {
        crudRoundTrip("/postgres");
    }

    @Test
    void serviceAdmin_Q2() throws IOException {
        crudRoundTrip("/postgres-admin");
    }

    @Test
    void tenantNoRole_Q3() throws IOException {
        crudRoundTrip("/postgres-tenant");
    }

    @Test
    void tenantAdmin_Q4() throws IOException {
        crudRoundTrip("/postgres-tenant-admin");
    }

    private void crudRoundTrip(String basePath) throws IOException {
        String itemName = service + "-e2e-" + UUID.randomUUID();

        // First call also lazily runs the migration on the resolved datasource; retry while it warms up.
        executeEventually(new Request.Builder().url(url(basePath + "/items")).delete().build(), 200);

        String createdBody = execute(new Request.Builder()
                .url(url(basePath + "/items"))
                .post(RequestBody.create("{\"name\":\"" + itemName + "\"}", JSON))
                .build(), 201);
        assertTrue(createdBody.contains(itemName), basePath + ": created item response must contain inserted name");

        String listBody = execute(new Request.Builder().url(url(basePath + "/items")).get().build(), 200);
        JsonObject response = GSON.fromJson(listBody, JsonObject.class);
        JsonArray items = response.getAsJsonArray("items");
        assertNotNull(items, basePath + ": items response field must be present");
        assertTrue(containsItemName(items, itemName), basePath + ": inserted item must be returned by GET");
    }

    private String url(String path) {
        return ClusterHelper.url(sampleService, path);
    }

    private static boolean containsItemName(JsonArray items, String itemName) {
        for (var item : items) {
            if (item.isJsonObject() && item.getAsJsonObject().has("name")
                    && itemName.equals(item.getAsJsonObject().get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private Secret waitForSecret(String name) {
        kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);
        Secret secret = kubernetesClient.secrets().inNamespace(namespace).withName(name).get();
        assertNotNull(secret, "Secret must exist: " + name);
        return secret;
    }

    private void assertSecret(Secret secret, String scope, String tenantId, String expectedUserRole) {
        assertNotNull(secret.getData(), "Secret data must not be null");
        assertTrue(secret.getData().containsKey(CONNECTION_PROPERTIES_KEY),
                "Secret must contain key: " + CONNECTION_PROPERTIES_KEY);
        assertTrue(secret.getData().containsKey(METADATA_KEY), "Secret must contain key: " + METADATA_KEY);

        JsonObject metadata = decodeSecretJson(secret, METADATA_KEY);
        assertEquals(POSTGRES_TYPE, metadata.get("type").getAsString(), "metadata type must match");

        JsonObject classifier = metadata.getAsJsonObject("classifier");
        assertNotNull(classifier, "metadata classifier must be present");
        assertEquals(service, classifier.get("microserviceName").getAsString());
        assertEquals(namespace, classifier.get("namespace").getAsString());
        assertEquals(scope, classifier.get("scope").getAsString());
        if (tenantId != null) {
            assertNotNull(classifier.get("tenantId"), "tenant classifier must carry tenantId");
            assertEquals(tenantId, classifier.get("tenantId").getAsString());
        }
        if (expectedUserRole != null) {
            assertNotNull(metadata.get("userRole"), "role-scoped secret must carry userRole in metadata");
            assertEquals(expectedUserRole, metadata.get("userRole").getAsString(), "metadata userRole must match the claim");
        }
    }

    private static JsonObject decodeSecretJson(Secret secret, String key) {
        String decodedJson = new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        return GSON.fromJson(decodedJson, JsonObject.class);
    }

    private GenericKubernetesResource waitForDesiredState(CustomResourceDefinitionContext crd, String name,
                                                          String desiredPhase, String desiredReadiness,
                                                          Set<String> desiredReadyReasons, String desiredStalling) {
        var resource = kubernetesClient.genericKubernetesResources(crd).inNamespace(namespace).withName(name);
        resource.waitUntilCondition(r -> isDesiredState(r, desiredPhase, desiredReadiness, desiredReadyReasons, desiredStalling),
                3, TimeUnit.MINUTES);
        GenericKubernetesResource result = resource.get();
        assertNotNull(result, "CR must exist: " + name);
        assertDesiredState(result, desiredPhase, desiredReadiness, desiredReadyReasons, desiredStalling);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static boolean isDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness,
                                          Set<String> desiredReadyReasons, String desiredStalling) {
        if (cr == null || cr.getAdditionalProperties() == null) {
            return false;
        }
        Map<String, Object> status = (Map<String, Object>) cr.getAdditionalProperties().get("status");
        if (status == null || !desiredPhase.equals(status.get("phase"))) {
            return false;
        }
        Map<String, Map<String, Object>> conditions = conditionMap(status);
        return desiredReadiness.equals(conditionValue(conditions, "Ready", "status"))
                && desiredReadyReasons.contains(conditionValue(conditions, "Ready", "reason"))
                && desiredStalling.equals(conditionValue(conditions, "Stalled", "status"));
    }

    @SuppressWarnings("unchecked")
    private static void assertDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness,
                                           Set<String> desiredReadyReasons, String desiredStalling) {
        Map<String, Object> status = (Map<String, Object>) cr.getAdditionalProperties().get("status");
        assertNotNull(status, "status must not be null");
        assertEquals(desiredPhase, status.get("phase"), "unexpected CR phase");

        Map<String, Map<String, Object>> conditions = conditionMap(status);
        assertEquals(desiredReadiness, conditionValue(conditions, "Ready", "status"), "unexpected Ready status");
        String readyReason = conditionValue(conditions, "Ready", "reason");
        assertTrue(desiredReadyReasons.contains(readyReason),
                "unexpected Ready reason: " + readyReason + ", expected one of " + desiredReadyReasons);
        assertEquals(desiredStalling, conditionValue(conditions, "Stalled", "status"), "unexpected Stalled status");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> conditionMap(Map<String, Object> status) {
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
        assertNotNull(conditions, "conditions must not be null");
        assertFalse(conditions.isEmpty(), "conditions must not be empty");
        return conditions.stream().collect(java.util.stream.Collectors.toMap(
                c -> String.valueOf(c.get("type")),
                c -> c
        ));
    }

    private static String conditionValue(Map<String, Map<String, Object>> conditions, String type, String field) {
        Map<String, Object> condition = conditions.get(type);
        if (condition == null || condition.get(field) == null) {
            return null;
        }
        return String.valueOf(condition.get(field));
    }
}
