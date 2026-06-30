package com.netcracker.it.testapps.gotestapp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GoTestAppServiceIT {

    private static final String SAMPLE_SERVICE_NAME = "go-test-app-service";
    private static final String CONNECTION_PROPERTIES_KEY = "connectionProperties.json";
    private static final String METADATA_KEY = "metadata.json";
    private static final String POSTGRES_TYPE = "postgresql";
    private static final String TENANT_ID = "acme";
    private static final String ADMIN_ROLE = "admin";
    private static final int HTTP_PORT = 8080;
    private static final MediaType JSON = MediaType.get("application/json");

    private static final String INTERNAL_DB_SERVICE = "go-test-app-service-postgres";
    private static final String INTERNAL_DB_TENANT = "go-test-app-service-postgres-tenant";
    private static final String CLAIM_SERVICE = "go-test-app-service-postgres-claim";
    private static final String CLAIM_SERVICE_ADMIN = "go-test-app-service-postgres-admin-claim";
    private static final String CLAIM_TENANT = "go-test-app-service-postgres-tenant-claim";
    private static final String CLAIM_TENANT_ADMIN = "go-test-app-service-postgres-tenant-admin-claim";
    private static final String SECRET_SERVICE = "go-test-app-service-postgres";
    private static final String SECRET_SERVICE_ADMIN = "go-test-app-service-postgres-admin";
    private static final String SECRET_TENANT = "go-test-app-service-postgres-tenant";
    private static final String SECRET_TENANT_ADMIN = "go-test-app-service-postgres-tenant-admin";

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
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(60))
            .build();

    private static KubernetesClient kubernetesClient;
    private static Process portForwardProcess;
    private static String namespace;
    private static URL sampleServiceUrl;

    @BeforeAll
    static void setUpSampleService() throws IOException {
        namespace = getRequiredPropertyOrEnv("clouds.cloud.namespaces.namespace");
        kubernetesClient = new KubernetesClientBuilder().build();

        waitForDesiredState(CRD_INTERNAL_DATABASE, INTERNAL_DB_SERVICE, "Succeeded", "True", "DatabaseProvisioned", "False");
        waitForDesiredState(CRD_INTERNAL_DATABASE, INTERNAL_DB_TENANT, "Succeeded", "True", "DatabaseProvisioned", "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_SERVICE, "Succeeded", "True", "SecretCreated", "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_SERVICE_ADMIN, "Succeeded", "True", "SecretCreated", "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_TENANT, "Succeeded", "True", "SecretCreated", "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_TENANT_ADMIN, "Succeeded", "True", "SecretCreated", "False");

        assertSecret(waitForSecret(SECRET_SERVICE), "service", null, null);
        assertSecret(waitForSecret(SECRET_SERVICE_ADMIN), "service", null, ADMIN_ROLE);
        assertSecret(waitForSecret(SECRET_TENANT), "tenant", TENANT_ID, null);
        assertSecret(waitForSecret(SECRET_TENANT_ADMIN), "tenant", TENANT_ID, ADMIN_ROLE);

        startPortForward();
    }

    @AfterAll
    static void closeResources() {
        stopPortForward();
        if (kubernetesClient != null) {
            kubernetesClient.close();
        }
    }

    private static void stopPortForward() {
        if (portForwardProcess != null) {
            portForwardProcess.destroy();
            try {
                if (!portForwardProcess.waitFor(5, TimeUnit.SECONDS)) {
                    portForwardProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                portForwardProcess.destroyForcibly();
            }
            portForwardProcess = null;
        }
    }

    // Each endpoint is backed by a datasource resolved from a distinct mounted secret matched by
    // (classifier, type, userRole); a successful round-trip proves that quadrant's secret matched.

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
        String itemName = "go-e2e-" + UUID.randomUUID();

        // First call also lazily runs the migration on the resolved datasource; retry while it warms up.
        executeEventually(new Request.Builder().url(sampleUrl(basePath + "/items")).delete().build(), 200);

        String createdBody = execute(new Request.Builder()
                .url(sampleUrl(basePath + "/items"))
                .post(RequestBody.create("{\"name\":\"" + itemName + "\"}", JSON))
                .build(), 201);
        assertTrue(createdBody.contains(itemName), basePath + ": created item response must contain inserted name");

        String listBody = execute(new Request.Builder().url(sampleUrl(basePath + "/items")).get().build(), 200);
        JsonObject response = GSON.fromJson(listBody, JsonObject.class);
        JsonArray items = response.getAsJsonArray("items");
        assertNotNull(items, basePath + ": items response field must be present");
        assertTrue(containsItemName(items, itemName), basePath + ": inserted item must be returned by GET");
    }

    @Test
    void testServiceWorksWithInvalidAggregatorUrl() throws IOException {
        var deployment = kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(SAMPLE_SERVICE_NAME)
                .get();
        assertNotNull(deployment, "Deployment must exist before test");

        var originalEnv = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        String originalAggregatorUrl = originalEnv.stream()
                .filter(env -> "DBAAS_AGENT".equals(env.getName()))
                .findFirst()
                .map(env -> env.getValue())
                .orElse(null);
        assertNotNull(originalAggregatorUrl, "DBAAS_AGENT env var must exist before test");

        try {
            deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                    .filter(env -> "DBAAS_AGENT".equals(env.getName()))
                    .findFirst()
                    .ifPresent(env -> env.setValue("http://invalid-aggregator.example.com:9999"));

            var updatedDeployment = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .resource(deployment)
                    .update();

            waitForDeploymentReady(updatedDeployment.getMetadata().getGeneration());
            restartPortForward();

            String itemName = "invalid-aggregator-test-" + UUID.randomUUID();

            executeEventually(new Request.Builder().url(sampleUrl("/postgres/items")).delete().build(), 200);

            String createdBody = execute(new Request.Builder()
                    .url(sampleUrl("/postgres/items"))
                    .post(RequestBody.create("{\"name\":\"" + itemName + "\"}", JSON))
                    .build(), 201);
            assertTrue(createdBody.contains(itemName),
                    "Service must successfully create item even with invalid aggregator URL");

            String listBody = execute(new Request.Builder().url(sampleUrl("/postgres/items")).get().build(), 200);
            JsonObject response = GSON.fromJson(listBody, JsonObject.class);
            JsonArray items = response.getAsJsonArray("items");
            assertNotNull(items, "items response field must be present");
            assertTrue(containsItemName(items, itemName),
                    "Service must successfully retrieve items even with invalid aggregator URL - proves it reads from mounted secret");

        } finally {
            var restoredDeployment = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .withName(SAMPLE_SERVICE_NAME)
                    .get();

            restoredDeployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                    .filter(env -> "DBAAS_AGENT".equals(env.getName()))
                    .findFirst()
                    .ifPresent(env -> env.setValue(originalAggregatorUrl));

            var restoredUpdatedDeployment = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .resource(restoredDeployment)
                    .update();

            waitForDeploymentReady(restoredUpdatedDeployment.getMetadata().getGeneration());
            restartPortForward();
        }
    }

    private static void startPortForward() throws IOException {
        int localPort = findFreePort();
        String podName = waitForReadyPodName();
        portForwardProcess = new ProcessBuilder(
                "kubectl", "-n", namespace, "port-forward", "pod/" + podName, localPort + ":" + HTTP_PORT
        )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        sampleServiceUrl = new URL("http://127.0.0.1:" + localPort);
        waitForServiceHealth();
    }

    private static void restartPortForward() throws IOException {
        stopPortForward();
        startPortForward();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitForServiceHealth() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        IOException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                execute(new Request.Builder().url(sampleUrl("/health")).get().build(), 200);
                return;
            } catch (IOException e) {
                lastError = e;
                sleep(Duration.ofSeconds(1));
            }
        }
        throw new AssertionError("Sample service did not become reachable through port-forward", lastError);
    }

    private static String getRequiredPropertyOrEnv(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv("ENV_" + name);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property/env: " + name);
        }
        return value;
    }

    private static String sampleUrl(String path) {
        return sampleServiceUrl.toString().replaceAll("/$", "") + path;
    }

    private static String execute(Request request, int expectedStatus) throws IOException {
        try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : responseBody.string();
            assertEquals(expectedStatus, response.code(), "unexpected response from " + request.url() + ": " + body);
            return body;
        }
    }

    private static String executeEventually(Request request, int expectedStatus) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        Throwable lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                return execute(request, expectedStatus);
            } catch (IOException | AssertionError e) {
                lastError = e;
                sleep(Duration.ofSeconds(2));
            }
        }
        if (lastError instanceof IOException ioException) {
            throw ioException;
        }
        if (lastError instanceof AssertionError assertionError) {
            throw assertionError;
        }
        throw new AssertionError("request did not complete before timeout: " + request.url());
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

    private static Secret waitForSecret(String name) {
        kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);
        Secret secret = kubernetesClient.secrets().inNamespace(namespace).withName(name).get();
        assertNotNull(secret, "Secret must exist: " + name);
        return secret;
    }

    private static void assertSecret(Secret secret, String scope, String tenantId, String expectedUserRole) {
        assertNotNull(secret.getData(), "Secret data must not be null");
        assertTrue(secret.getData().containsKey(CONNECTION_PROPERTIES_KEY),
                "Secret must contain key: " + CONNECTION_PROPERTIES_KEY);
        assertTrue(secret.getData().containsKey(METADATA_KEY), "Secret must contain key: " + METADATA_KEY);

        JsonObject metadata = decodeSecretJson(secret, METADATA_KEY);
        assertEquals(POSTGRES_TYPE, metadata.get("type").getAsString(), "metadata type must match");

        JsonObject classifier = metadata.getAsJsonObject("classifier");
        assertNotNull(classifier, "metadata classifier must be present");
        assertEquals(SAMPLE_SERVICE_NAME, classifier.get("microserviceName").getAsString());
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

    private static GenericKubernetesResource waitForDesiredState(CustomResourceDefinitionContext crd, String name,
                                                                 String desiredPhase, String desiredReadiness,
                                                                 String desiredReadyReason, String desiredStalling) {
        var resource = kubernetesClient.genericKubernetesResources(crd).inNamespace(namespace).withName(name);
        resource.waitUntilCondition(r -> isDesiredState(r, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling),
                3, TimeUnit.MINUTES);
        GenericKubernetesResource result = resource.get();
        assertNotNull(result, "CR must exist: " + name);
        assertDesiredState(result, desiredPhase, desiredReadiness, desiredReadyReason, desiredStalling);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static boolean isDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness,
                                          String desiredReadyReason, String desiredStalling) {
        if (cr == null || cr.getAdditionalProperties() == null) {
            return false;
        }
        Map<String, Object> status = (Map<String, Object>) cr.getAdditionalProperties().get("status");
        if (status == null || !desiredPhase.equals(status.get("phase"))) {
            return false;
        }
        Map<String, Map<String, Object>> conditions = conditionMap(status);
        return desiredReadiness.equals(conditionValue(conditions, "Ready", "status"))
                && desiredReadyReason.equals(conditionValue(conditions, "Ready", "reason"))
                && desiredStalling.equals(conditionValue(conditions, "Stalled", "status"));
    }

    @SuppressWarnings("unchecked")
    private static void assertDesiredState(GenericKubernetesResource cr, String desiredPhase, String desiredReadiness,
                                           String desiredReadyReason, String desiredStalling) {
        Map<String, Object> status = (Map<String, Object>) cr.getAdditionalProperties().get("status");
        assertNotNull(status, "status must not be null");
        assertEquals(desiredPhase, status.get("phase"), "unexpected CR phase");

        Map<String, Map<String, Object>> conditions = conditionMap(status);
        assertEquals(desiredReadiness, conditionValue(conditions, "Ready", "status"), "unexpected Ready status");
        assertEquals(desiredReadyReason, conditionValue(conditions, "Ready", "reason"), "unexpected Ready reason");
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restartDeploymentAndWait() throws IOException {
        kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(SAMPLE_SERVICE_NAME)
                .rolling()
                .restart();
        var deployment = kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(SAMPLE_SERVICE_NAME)
                .get();
        assertNotNull(deployment, "Deployment must exist after restart request");
        waitForDeploymentReady(deployment.getMetadata().getGeneration());
        restartPortForward();
    }

    private static void waitForDeploymentReady(Long targetGeneration) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            var deployment = kubernetesClient.apps().deployments()
                    .inNamespace(namespace)
                    .withName(SAMPLE_SERVICE_NAME)
                    .get();
            if (deployment == null || deployment.getStatus() == null || deployment.getSpec() == null) {
                sleep(Duration.ofSeconds(2));
                continue;
            }

            int desiredReplicas = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
            Long observedGeneration = deployment.getStatus().getObservedGeneration();
            Integer replicas = deployment.getStatus().getReplicas();
            Integer readyReplicas = deployment.getStatus().getReadyReplicas();
            Integer updatedReplicas = deployment.getStatus().getUpdatedReplicas();
            Integer availableReplicas = deployment.getStatus().getAvailableReplicas();
            Integer unavailableReplicas = deployment.getStatus().getUnavailableReplicas();

            if (observedGeneration != null
                    && observedGeneration >= targetGeneration
                    && replicas != null
                    && replicas == desiredReplicas
                    && readyReplicas != null
                    && readyReplicas == desiredReplicas
                    && updatedReplicas != null
                    && updatedReplicas == desiredReplicas
                    && availableReplicas != null
                    && availableReplicas == desiredReplicas
                    && (unavailableReplicas == null || unavailableReplicas == 0)) {
                return;
            }

            sleep(Duration.ofSeconds(2));
        }

        throw new AssertionError("Deployment did not roll out and become ready within timeout");
    }

    private static String waitForReadyPodName() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            var pod = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabel("app.kubernetes.io/name", SAMPLE_SERVICE_NAME)
                    .list()
                    .getItems()
                    .stream()
                    .filter(GoTestAppServiceIT::isReadyPod)
                    .max((left, right) -> left.getMetadata().getCreationTimestamp()
                            .compareTo(right.getMetadata().getCreationTimestamp()));
            if (pod.isPresent()) {
                return pod.get().getMetadata().getName();
            }
            sleep(Duration.ofSeconds(2));
        }
        throw new AssertionError("No ready pod found for " + SAMPLE_SERVICE_NAME);
    }

    private static boolean isReadyPod(io.fabric8.kubernetes.api.model.Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }
}
