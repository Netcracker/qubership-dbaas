package com.netcracker.it.testapps.springtestapp;

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

/**
 * Black-box integration test for the Spring mounted-secret PostgreSQL client path, mirroring
 * {@code GoTestAppServiceIT}.
 *
 * <p>The service is deployed with its aggregator URL ({@code API_DBAAS_ADDRESS}) pointed at an
 * <b>unreachable</b> host on purpose. The {@code InternalDatabase}/{@code DatabaseSecretClaim} are
 * provisioned by the operator (which talks to the real aggregator), but the service itself can only
 * reach its database through the mounted secret. So if DML succeeds at all, it proves the
 * connection came from the mount, not REST — a REST call to a dead host cannot return one.
 */
public class SpringTestAppServiceIT {

    private static final String SAMPLE_SERVICE_NAME = "spring-test-app-service";
    private static final String INTERNAL_DATABASE_NAME = "spring-test-app-service-postgres";
    private static final String DATABASE_SECRET_CLAIM_NAME = "spring-test-app-service-postgres-claim";
    private static final String DATABASE_SECRET_NAME = "spring-test-app-service-postgres";
    private static final String CONNECTION_PROPERTIES_KEY = "connectionProperties.json";
    private static final String METADATA_KEY = "metadata.json";
    private static final String POSTGRES_TYPE = "postgresql";
    private static final int HTTP_PORT = 8080;
    private static final MediaType JSON = MediaType.get("application/json");

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

        waitForDesiredState(CRD_INTERNAL_DATABASE, INTERNAL_DATABASE_NAME,
                "Succeeded", "True", "DatabaseProvisioned", "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, DATABASE_SECRET_CLAIM_NAME,
                "Succeeded", "True", "SecretCreated", "False");

        Secret secret = waitForSecret(DATABASE_SECRET_NAME);
        assertMountedSecretContent(secret);

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

    /**
     * The service is configured with an unreachable aggregator URL, so a successful insert/list
     * proves the database connection (and the Flyway migration that created the table) came from
     * the mounted secret rather than a REST call to dbaas-aggregator.
     */
    @Test
    void testServiceResolvesDatabaseFromMountedSecretWithoutAggregator() throws IOException {
        String itemName = "spring-test-app-e2e-" + UUID.randomUUID();

        executeEventually(new Request.Builder().url(sampleUrl("/postgres/items")).delete().build(), 200);

        String createdBody = execute(new Request.Builder()
                .url(sampleUrl("/postgres/items"))
                .post(RequestBody.create("{\"name\":\"" + itemName + "\"}", JSON))
                .build(), 201);
        assertTrue(createdBody.contains(itemName), "created item response must contain inserted name");

        String listBody = execute(new Request.Builder().url(sampleUrl("/postgres/items")).get().build(), 200);
        JsonObject response = GSON.fromJson(listBody, JsonObject.class);
        JsonArray items = response.getAsJsonArray("items");
        assertNotNull(items, "items response field must be present");
        assertTrue(containsItemName(items, itemName), "inserted item must be returned by GET /postgres/items");
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

    private static void assertMountedSecretContent(Secret secret) {
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
        assertEquals("service", classifier.get("scope").getAsString());
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

    private static String waitForReadyPodName() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            var pod = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabel("app.kubernetes.io/name", SAMPLE_SERVICE_NAME)
                    .list()
                    .getItems()
                    .stream()
                    .filter(SpringTestAppServiceIT::isReadyPod)
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
