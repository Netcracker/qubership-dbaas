package com.netcracker.it.testapps.quarkustestapp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import com.netcracker.it.testapps.helpers.ClusterHelper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.netcracker.it.testapps.helpers.ClusterHelper.execute;
import static com.netcracker.it.testapps.helpers.ClusterHelper.executeEventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box integration test for the Quarkus mounted-secret PostgreSQL client across all four
 * (scope × role) quadrants, mirroring {@code GoTestAppServiceIT} / {@code SpringTestAppServiceIT}.
 *
 * <p>The service is deployed with its aggregator URL ({@code API_DBAAS_ADDRESS}) pointed at an
 * <b>unreachable</b> host on purpose. The operator (which uses the real aggregator) provisions two
 * databases (service + tenant) and four {@code DatabaseSecretClaim}s — service/tenant × no-role/admin —
 * each materialized into its own mounted secret. The app resolves a matching datasource per quadrant
 * from those secrets, so a successful insert/list on each endpoint proves the connection came from the
 * mount, not REST (a dead host cannot return one):
 * <pre>
 *   /postgres               Q1 service + no role     /postgres-admin         Q2 service + admin
 *   /postgres-tenant        Q3 tenant  + no role     /postgres-tenant-admin  Q4 tenant  + admin
 * </pre>
 */
public class QuarkusTestAppServiceIT {

    private static final String SAMPLE_SERVICE_NAME = "quarkus-test-app-service";
    private static final String CONNECTION_PROPERTIES_KEY = "connectionProperties.json";
    private static final String METADATA_KEY = "metadata.json";
    private static final String POSTGRES_TYPE = "postgresql";
    private static final String TENANT_ID = "acme";
    private static final String ADMIN_ROLE = "admin";
    private static final int HTTP_PORT = 8080;
    private static final MediaType JSON = MediaType.get("application/json");

    private static final String INTERNAL_DB_SERVICE = "quarkus-test-app-service-postgres";
    private static final String INTERNAL_DB_TENANT = "quarkus-test-app-service-postgres-tenant";
    private static final String CLAIM_SERVICE = "quarkus-test-app-service-postgres-claim";
    private static final String CLAIM_SERVICE_ADMIN = "quarkus-test-app-service-postgres-admin-claim";
    private static final String CLAIM_TENANT = "quarkus-test-app-service-postgres-tenant-claim";
    private static final String CLAIM_TENANT_ADMIN = "quarkus-test-app-service-postgres-tenant-admin-claim";
    private static final String SECRET_SERVICE = "quarkus-test-app-service-postgres";
    private static final String SECRET_SERVICE_ADMIN = "quarkus-test-app-service-postgres-admin";
    private static final String SECRET_TENANT = "quarkus-test-app-service-postgres-tenant";
    private static final String SECRET_TENANT_ADMIN = "quarkus-test-app-service-postgres-tenant-admin";

    /**
     * Ready reasons that all mean the claim is healthy. The reason reports the latest reconciliation
     * outcome rather than a permanent creation marker, so a claim whose credential was rotated
     * between passes reports SecretRotated or SecretUpToDate while being exactly as ready as one
     * reporting SecretCreated. Requiring a single reason would tie the test to reconciliation
     * history instead of current readiness.
     */
    private static final Set<String> SUCCESSFUL_SECRET_READY_REASONS =
            Set.of("SecretCreated", "SecretRotated", "SecretUpToDate");

    /** An InternalDatabase has only one successful Ready reason, so its check stays exact. */
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

    private static String namespace;
    private static LocalPortForward sampleService;

    @BeforeAll
    static void setUpSampleService() throws IOException {
        namespace = ClusterHelper.namespace();

        waitForDesiredState(CRD_INTERNAL_DATABASE, INTERNAL_DB_SERVICE, "Succeeded", "True", DATABASE_PROVISIONED_REASONS, "False");
        waitForDesiredState(CRD_INTERNAL_DATABASE, INTERNAL_DB_TENANT, "Succeeded", "True", DATABASE_PROVISIONED_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_SERVICE, "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_SERVICE_ADMIN, "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_TENANT, "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");
        waitForDesiredState(CRD_DATABASE_SECRET_CLAIM, CLAIM_TENANT_ADMIN, "Succeeded", "True", SUCCESSFUL_SECRET_READY_REASONS, "False");

        assertSecret(waitForSecret(SECRET_SERVICE), "service", null, null);
        assertSecret(waitForSecret(SECRET_SERVICE_ADMIN), "service", null, ADMIN_ROLE);
        assertSecret(waitForSecret(SECRET_TENANT), "tenant", TENANT_ID, null);
        assertSecret(waitForSecret(SECRET_TENANT_ADMIN), "tenant", TENANT_ID, ADMIN_ROLE);

        sampleService = ClusterHelper.toService(SAMPLE_SERVICE_NAME, HTTP_PORT);
        ClusterHelper.awaitHealthy(ClusterHelper.url(sampleService, "/health"), SAMPLE_SERVICE_NAME);
    }

    @AfterAll
    static void closeResources() throws IOException {
        sampleService.close();
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
        String itemName = "quarkus-e2e-" + UUID.randomUUID();

        // First call also lazily runs the Flyway migration on the resolved datasource; retry while it warms up.
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

    private static String sampleUrl(String path) {
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
