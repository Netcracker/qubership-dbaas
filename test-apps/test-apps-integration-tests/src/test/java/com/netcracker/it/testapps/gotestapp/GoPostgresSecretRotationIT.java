package com.netcracker.it.testapps.gotestapp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a Go microservice survives a real PostgreSQL credential rotation without restarting.
 *
 * <p>The scenario rotates the admin credential through the DBaaS password-change API, lets the
 * operator republish the generated Secret, then closes the PostgreSQL frontend and terminates the
 * live database session through HAProxy. With no connection left and none available, a real
 * transaction has to fail; reopening the frontend requires the application to reconnect with the new
 * credential. The whole sequence must fit inside one deadline measured from the rotation request: a
 * rotation that only recovers after several minutes is not a recovery a caller can rely on.
 *
 * <p>Two proxies make the scenario observable. DBaaS Proxy sits between the operator and the
 * aggregator and rewrites the database endpoint to the TCP proxy, so the application's traffic is
 * routed somewhere a test can interrupt. HAProxy carries that traffic and terminates it on command.
 * Both come from the {@code test-apps/database-proxy-test-stack} fixture, which must be deployed
 * before the application's database resources are created.
 *
 * <p>This class runs alone in its own Maven invocation. It rotates a credential shared with every
 * other test in the namespace, so a concurrent class would see failures it did not cause.
 */
public class GoPostgresSecretRotationIT {

    private static final String SERVICE_NAME = "go-test-app-service";
    private static final String SERVICE_LABEL = "app.kubernetes.io/name";
    private static final String SECRET_NAME = "go-test-app-service-postgres-admin";
    private static final String CLAIM_NAME = "go-test-app-service-postgres-admin-claim";
    private static final String INTERNAL_DATABASE_NAME = "go-test-app-service-postgres";
    private static final String PROBE_PATH = "/postgres-admin/rotation-probe";
    private static final String DATABASE_TYPE = "postgresql";
    private static final String DATABASE_ROLE = "admin";
    private static final String CONNECTION_PROPERTIES_KEY = "connectionProperties.json";
    private static final String MOUNT_PATH =
            "/etc/secrets/dbaas-secrets/" + SECRET_NAME + "/" + CONNECTION_PROPERTIES_KEY;

    /** Port the fixture's HAProxy listens on for PostgreSQL, and the objects it names after it. */
    private static final int TCP_PROXY_PORT = 8801;
    private static final String FRONTEND = "fe_" + TCP_PROXY_PORT;
    private static final String BACKEND = "be_" + TCP_PROXY_PORT;
    private static final String SERVER = "srv_" + TCP_PROXY_PORT;

    private static final int SERVICE_HTTP_PORT = 8080;
    private static final int PROXY_HTTP_PORT = 8080;

    /** Short enough that a stalled attempt still leaves room to retry before the deadline. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private static final MediaType JSON = MediaType.get("application/json");
    private static final Gson GSON = new Gson();

    private static final CustomResourceDefinitionContext CRD_INTERNAL_DATABASE = crd("internaldatabases");
    private static final CustomResourceDefinitionContext CRD_DATABASE_SECRET_CLAIM = crd("databasesecretclaims");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT)
            .readTimeout(REQUEST_TIMEOUT)
            .writeTimeout(REQUEST_TIMEOUT)
            .build();

    private static KubernetesClient kubernetesClient;
    private static String namespace;
    private static String proxyServiceName;
    private static String dbaCredentials;
    private static Duration rotationTimeout;

    private static Process serviceForward;
    private static Process proxyForward;
    private static String serviceUrl;
    private static String proxyUrl;

    @BeforeAll
    static void setUp() {
        namespace = requiredConfig("clouds.cloud.namespaces.namespace");
        proxyServiceName = optionalConfig("rotation.dbaas.proxy.service", "dbaas-proxy");
        rotationTimeout = Duration.ofSeconds(Long.parseLong(optionalConfig("rotation.timeout.seconds", "120")));
        // Required rather than defaulted: a credential baked into a test class outlives the
        // environment it was meant for.
        dbaCredentials = Credentials.basic(
                requiredConfig("rotation.cluster.dba.username"),
                requiredConfig("rotation.cluster.dba.password"));

        kubernetesClient = new KubernetesClientBuilder().build();
    }

    @AfterAll
    static void tearDown() {
        stopForward(serviceForward);
        stopForward(proxyForward);
        serviceForward = null;
        proxyForward = null;
        if (kubernetesClient != null) {
            kubernetesClient.close();
        }
    }

    /**
     * One ordered method rather than several: every step depends on state the previous one
     * established, and a half-rotated credential is not something a later method could meaningfully
     * assert against.
     */
    @Test
    void rotateAdminCredential_reconnectsWithoutRestartingThePod() throws Exception {
        // 1-2. The operator must have provisioned the database and published the Secret.
        waitForSucceeded(CRD_INTERNAL_DATABASE, INTERNAL_DATABASE_NAME);
        waitForSucceeded(CRD_DATABASE_SECRET_CLAIM, CLAIM_NAME);

        // 3. Retain what the rotation has to change. The resource version alone is not enough: the
        // operator can rewrite a Secret with identical content, and that is not a rotation.
        Secret secretBefore = readSecret();
        String resourceVersionBefore = secretBefore.getMetadata().getResourceVersion();
        String passwordHashBefore = passwordHash(secretBefore);

        // 4. If the endpoint still points at the database, the application never traverses HAProxy,
        // and the session this test terminates is not the one it is using.
        JsonObject connectionBefore = connectionProperties(secretBefore);
        assertEquals(TCP_PROXY_PORT, connectionBefore.get("port").getAsInt(),
                "Secret must point at the TCP proxy port. Deploy database-proxy-test-stack and route the "
                        + "operator through DBaaS Proxy before the claim is reconciled.");
        assertTrue(connectionBefore.get("host").getAsString().startsWith("tcp-proxy"),
                "Secret host must be the TCP proxy, but was " + connectionBefore.get("host").getAsString());

        // 5. Pod identity, so a silent restart cannot pass as a recovery.
        Pod podBefore = readServicePod();
        String podNameBefore = podBefore.getMetadata().getName();
        String podUidBefore = podBefore.getMetadata().getUid();
        int restartsBefore = restartCount(podBefore);
        long deploymentGenerationBefore = deploymentObservedGeneration();

        startPortForwards(podNameBefore);

        // 6. A real transaction on the pre-rotation credential. Without this baseline, a later
        // success could just mean the endpoint was never exercised.
        assertEquals(200, probe(UUID.randomUUID().toString()),
                "Baseline rotation probe must succeed before the credential is rotated");

        // 7-8. Confirm the session on the proxy rather than inferring it from HTTP success.
        assertTrue(hasPostgresSession(), "HAProxy must carry a PostgreSQL session before rotation "
                + "(frontend " + FRONTEND + ", backend " + BACKEND + ")");

        // 9-10. The deadline covers everything the platform has to do, so it starts here.
        long rotationStartNanos = System.nanoTime();
        changePassword();

        // 11. The operator polls the changed-databases feed, then republishes the Secret.
        Secret secretAfter = awaitRotatedSecret(resourceVersionBefore, passwordHashBefore, rotationStartNanos);
        String passwordHashAfter = passwordHash(secretAfter);
        long rotationToSecretMs = elapsedMs(rotationStartNanos);
        assertNotEquals(passwordHashBefore, passwordHashAfter, "Rotation must change the password");

        // 12. The established session outlives the password change, because PostgreSQL authenticates
        // only when a connection is opened. That is why the session has to be terminated to observe
        // anything at all.
        assertTrue(hasPostgresSession(), "HAProxy session must survive the password change, "
                + "otherwise the shutdown below proves nothing");

        // 13. Block new PostgreSQL connections. An established session keeps working through a
        // disabled frontend, so this only stops the application from silently opening a replacement
        // between the shutdown below and the assertion that follows it.
        setPostgresFrontend("disable");
        long shutdownNanos;
        long rotationToShutdownMs;
        try {
            // 14-15. Terminate the session the application is using and confirm it is gone.
            shutdownPostgresSessions();
            awaitPostgresSessionShutdown();
            shutdownNanos = System.nanoTime();
            rotationToShutdownMs = elapsedMs(rotationStartNanos);

            // 16. The old connection is gone and no new one can be opened, so a real transaction has
            // to fail. Without this step the test never observes a failed database operation, and the
            // later success proves nothing about reconnection. The status has to be exactly 500: that
            // is what the application returns when the datasource or its transaction fails, so a
            // malformed request or a broken port-forward cannot satisfy the step by accident.
            assertEquals(500, probe(UUID.randomUUID().toString()),
                    "Rotation probe must fail after existing sessions are closed while the frontend is disabled");
        } finally {
            // A failed assertion above must not leave the frontend disabled for later tests.
            setPostgresFrontend("enable");
        }

        // 17. Retry until the client rebuilds its pool from the new mounted credential. The deadline
        // still runs from the rotation request, so the disabled window is part of the same budget.
        int attempts = awaitProbeRecovery(rotationStartNanos);
        long rotationToRecoveryMs = elapsedMs(rotationStartNanos);
        long shutdownToRecoveryMs = elapsedMs(shutdownNanos);

        // 18. The recovery has to come from the mount, not from a stale value that still works.
        assertEquals(passwordHashAfter, mountedPasswordHash(podNameBefore),
                "Mounted " + CONNECTION_PROPERTIES_KEY + " must carry the same password as the Secret");

        // 19-20. The pod must be the one the test started with.
        Pod podAfter = readServicePod();
        assertEquals(podNameBefore, podAfter.getMetadata().getName(), "Pod must not be replaced");
        assertEquals(podUidBefore, podAfter.getMetadata().getUid(), "Pod UID must not change");
        assertEquals(restartsBefore, restartCount(podAfter), "Container must not restart");
        assertEquals(deploymentGenerationBefore, deploymentObservedGeneration(),
                "Deployment must not roll out during the rotation");

        // Hashes and durations only; the values behind them never reach the log.
        System.out.printf(
                "rotation timings: rotationToSecretMs=%d rotationToShutdownMs=%d shutdownToRecoveryMs=%d "
                        + "rotationToRecoveryMs=%d probeAttempts=%d%n",
                rotationToSecretMs, rotationToShutdownMs, shutdownToRecoveryMs, rotationToRecoveryMs, attempts);

        assertTrue(rotationToRecoveryMs < rotationTimeout.toMillis(),
                "Rotation to recovery took " + rotationToRecoveryMs + " ms, deadline is "
                        + rotationTimeout.toMillis() + " ms");
    }

    // ── rotation steps ─────────────────────────────────────────────────────────────────────────

    /**
     * Rotates the admin credential through the real DBaaS API. The aggregator adds the namespace
     * from the path to the classifier, so the body carries only the fields that identify the
     * database within it.
     */
    private static void changePassword() throws IOException {
        JsonObject classifier = new JsonObject();
        classifier.addProperty("microserviceName", SERVICE_NAME);
        classifier.addProperty("scope", "service");

        JsonObject body = new JsonObject();
        body.add("classifier", classifier);
        body.addProperty("type", DATABASE_TYPE);
        body.addProperty("userRole", DATABASE_ROLE);

        Request request = new Request.Builder()
                .url(proxyUrl + "/api/v3/dbaas/namespaces/" + namespace + "/password-changes")
                .header("Authorization", dbaCredentials)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            String responseBody = bodyOf(response);
            assertEquals(200, response.code(),
                    "Password change request failed. Inspect the DBaaS Proxy and aggregator logs.");

            JsonObject parsed = GSON.fromJson(responseBody, JsonObject.class);
            assertNotNull(parsed, "Password change response must be JSON");
            int changed = parsed.has("changed") ? parsed.getAsJsonArray("changed").size() : 0;
            int failed = parsed.has("failed") ? parsed.getAsJsonArray("failed").size() : 0;
            assertEquals(0, failed, "Password change must not report failed databases");
            assertEquals(1, changed, "Password change must report exactly one changed database");
        }
    }

    /** Polls the Secret until the operator republishes it with a different password. */
    private static Secret awaitRotatedSecret(String resourceVersionBefore, String passwordHashBefore,
                                             long rotationStartNanos) {
        long deadline = rotationStartNanos + rotationTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            Secret secret = readSecret();
            boolean republished = !resourceVersionBefore.equals(secret.getMetadata().getResourceVersion());
            if (republished && !passwordHashBefore.equals(passwordHash(secret))) {
                return secret;
            }
            sleep(RETRY_DELAY);
        }
        throw new AssertionError("Operator did not republish " + SECRET_NAME + " with a new password within "
                + rotationTimeout.toMillis() + " ms. Check the rotation poller and the changed-databases feed.");
    }

    /**
     * Retries the probe until it commits a transaction on the rotated credential. Recovery inside
     * the first attempt is a valid outcome: the client can refresh its pool within one request.
     */
    private static int awaitProbeRecovery(long rotationStartNanos) throws IOException {
        long deadline = rotationStartNanos + rotationTimeout.toNanos();
        int attempts = 0;
        int lastStatus = -1;
        while (System.nanoTime() < deadline) {
            attempts++;
            try {
                lastStatus = probe(UUID.randomUUID().toString());
                if (lastStatus == 200) {
                    return attempts;
                }
            } catch (IOException e) {
                lastStatus = -1;
            }
            sleep(RETRY_DELAY);
        }
        throw new AssertionError("Application did not recover within " + rotationTimeout.toMillis()
                + " ms of the rotation. Attempts: " + attempts + ", last probe status: " + lastStatus);
    }

    private static int probe(String probeId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("probeId", probeId);
        Request request = new Request.Builder()
                .url(serviceUrl + PROBE_PATH)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            bodyOf(response);
            return response.code();
        }
    }

    // ── proxy control ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when HAProxy reports a session on the PostgreSQL frontend. Asking the proxy
     * directly separates "the application holds a connection" from "an HTTP call happened to
     * succeed".
     */
    private static boolean hasPostgresSession() throws IOException {
        String sessions = haproxyCommand("show sess");
        return sessions.contains(FRONTEND) || sessions.contains(BACKEND) || sessions.contains(SERVER);
    }

    private static String haproxyCommand(String command) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("cmd", command);
        Request request = new Request.Builder()
                .url(proxyUrl + "/haproxy/cmd")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            String responseBody = bodyOf(response);
            assertEquals(200, response.code(), "HAProxy command '" + command + "' failed: " + responseBody);
            // DBaaS Proxy returns the raw HAProxy output as a JSON string.
            return GSON.fromJson(responseBody, String.class);
        }
    }

    /** Terminates every PostgreSQL session on the proxy, which is what forces the client to reconnect. */
    private static void shutdownPostgresSessions() throws IOException {
        Request request = new Request.Builder()
                .url(proxyUrl + "/haproxy/shutdown-sessions/" + DATABASE_TYPE)
                .post(RequestBody.create("", JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            String responseBody = bodyOf(response);
            assertEquals(200, response.code(),
                    "DBaaS Proxy must terminate the PostgreSQL sessions: " + responseBody);
        }
    }

    /**
     * Enables or disables the PostgreSQL frontend on the proxy. Disabling it rejects new connections
     * while leaving established ones alone, which is what turns the session shutdown into a state the
     * application cannot recover from on its own.
     *
     * @param action {@code disable} or {@code enable}
     */
    private static void setPostgresFrontend(String action) throws IOException {
        Request request = new Request.Builder()
                .url(proxyUrl + "/haproxy/frontend/" + action + "/" + DATABASE_TYPE)
                .post(RequestBody.create("", JSON))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            String responseBody = bodyOf(response);
            assertEquals(200, response.code(),
                    "DBaaS Proxy must " + action + " the PostgreSQL frontend: " + responseBody);
        }
    }

    /** Confirms that HAProxy removed the established session before the recovery probe starts. */
    private static void awaitPostgresSessionShutdown() throws IOException {
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!hasPostgresSession()) {
                return;
            }
            sleep(RETRY_DELAY);
        }
        throw new AssertionError("HAProxy still reports the PostgreSQL session after the shutdown command");
    }

    // ── Kubernetes reads ───────────────────────────────────────────────────────────────────────

    private static Secret readSecret() {
        Secret secret = kubernetesClient.secrets().inNamespace(namespace).withName(SECRET_NAME).get();
        assertNotNull(secret, "Secret must exist: " + SECRET_NAME);
        assertNotNull(secret.getData(), "Secret must have data: " + SECRET_NAME);
        return secret;
    }

    private static JsonObject connectionProperties(Secret secret) {
        String encoded = secret.getData().get(CONNECTION_PROPERTIES_KEY);
        assertNotNull(encoded, "Secret must contain " + CONNECTION_PROPERTIES_KEY);
        String json = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return GSON.fromJson(json, JsonObject.class);
    }

    /**
     * Hashes the password before comparison so the clear-text value is never printed.
     */
    private static String passwordHash(Secret secret) {
        JsonObject properties = connectionProperties(secret);
        assertTrue(properties.has("password"), CONNECTION_PROPERTIES_KEY + " must contain a password");
        return sha256(properties.get("password").getAsString());
    }

    /**
     * Reads the projected Secret from inside the running pod. The kubelet updates the mount
     * asynchronously, so matching this against the Secret is what proves the application could
     * actually see the new credential.
     */
    private static String mountedPasswordHash(String podName) {
        String json = execInPod(podName, "cat", MOUNT_PATH);
        JsonObject properties = GSON.fromJson(json, JsonObject.class);
        assertNotNull(properties, "Mounted " + CONNECTION_PROPERTIES_KEY + " must contain a JSON object");
        assertTrue(properties.has("password"), "Mounted " + CONNECTION_PROPERTIES_KEY + " must contain a password");
        return sha256(properties.get("password").getAsString());
    }

    private static String execInPod(String podName, String... command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode;
        try (var exec = kubernetesClient.pods().inNamespace(namespace).withName(podName)
                .writingOutput(out)
                .writingError(err)
                .exec(command)) {
            exitCode = exec.exitCode().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while reading " + MOUNT_PATH + " from " + podName, e);
        } catch (Exception e) {
            throw new AssertionError("Could not read " + MOUNT_PATH + " from " + podName
                    + " (stderr: " + err.toString(StandardCharsets.UTF_8) + ")", e);
        }
        String output = out.toString(StandardCharsets.UTF_8).trim();
        if (exitCode != 0 || output.isEmpty()) {
            throw new AssertionError("Reading " + MOUNT_PATH + " from " + podName + " exited " + exitCode
                    + " (stderr: " + err.toString(StandardCharsets.UTF_8) + ")");
        }
        return output;
    }

    private static Pod readServicePod() {
        List<Pod> pods = kubernetesClient.pods().inNamespace(namespace)
                .withLabel(SERVICE_LABEL, SERVICE_NAME)
                .list()
                .getItems()
                .stream()
                .filter(GoPostgresSecretRotationIT::isReady)
                .toList();
        assertEquals(1, pods.size(), "Exactly one ready " + SERVICE_NAME + " pod must exist, found " + pods.size()
                + ". A second pod would make the unchanged-pod assertions meaningless.");
        return pods.get(0);
    }

    private static boolean isReady(Pod pod) {
        return pod.getStatus() != null
                && pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private static int restartCount(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return 0;
        }
        return pod.getStatus().getContainerStatuses().stream()
                .mapToInt(status -> status.getRestartCount() == null ? 0 : status.getRestartCount())
                .sum();
    }

    /** A changed observedGeneration means the Deployment rolled, which invalidates the whole scenario. */
    private static long deploymentObservedGeneration() {
        var deployment = kubernetesClient.apps().deployments().inNamespace(namespace).withName(SERVICE_NAME).get();
        assertNotNull(deployment, "Deployment must exist: " + SERVICE_NAME);
        Long observed = deployment.getStatus() == null ? null : deployment.getStatus().getObservedGeneration();
        return observed == null ? -1L : observed;
    }

    private static void waitForSucceeded(CustomResourceDefinitionContext crd, String name) {
        var resource = kubernetesClient.genericKubernetesResources(crd).inNamespace(namespace).withName(name);
        resource.waitUntilCondition(GoPostgresSecretRotationIT::isSucceededAndReady, 3, TimeUnit.MINUTES);
        GenericKubernetesResource result = resource.get();
        assertNotNull(result, "Custom resource must exist: " + name);
        assertTrue(isSucceededAndReady(result), "Custom resource must be Succeeded and Ready: " + name);
    }

    @SuppressWarnings("unchecked")
    private static boolean isSucceededAndReady(GenericKubernetesResource cr) {
        if (cr == null || cr.getAdditionalProperties() == null) {
            return false;
        }
        Map<String, Object> status = (Map<String, Object>) cr.getAdditionalProperties().get("status");
        if (status == null || !"Succeeded".equals(status.get("phase"))) {
            return false;
        }
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
        if (conditions == null) {
            return false;
        }
        return conditions.stream().anyMatch(condition ->
                "Ready".equals(condition.get("type")) && "True".equals(String.valueOf(condition.get("status"))));
    }

    private static CustomResourceDefinitionContext crd(String plural) {
        return new CustomResourceDefinitionContext.Builder()
                .withGroup("dbaas.netcracker.com")
                .withVersion("v1")
                .withPlural(plural)
                .withScope("Namespaced")
                .build();
    }

    // ── port forwarding ────────────────────────────────────────────────────────────────────────

    /**
     * Forwards the application pod and DBaaS Proxy separately. The application is addressed by pod
     * rather than by Service so every probe reaches the same pod whose restart count is asserted.
     */
    private static void startPortForwards(String podName) throws IOException {
        int servicePort = findFreePort();
        serviceForward = portForward("pod/" + podName, servicePort, SERVICE_HTTP_PORT);
        serviceUrl = "http://127.0.0.1:" + servicePort;

        int proxyPort = findFreePort();
        proxyForward = portForward("service/" + proxyServiceName, proxyPort, PROXY_HTTP_PORT);
        proxyUrl = "http://127.0.0.1:" + proxyPort;

        awaitReachable(serviceUrl + "/health", "application", serviceForward);
        awaitReachable(proxyUrl + "/health", "DBaaS Proxy", proxyForward);
    }

    private static Process portForward(String target, int localPort, int remotePort) throws IOException {
        return new ProcessBuilder("kubectl", "-n", namespace, "port-forward", target, localPort + ":" + remotePort)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static void awaitReachable(String url, String what, Process process) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        IOException lastError = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError("kubectl port-forward to " + what + " exited with code "
                        + process.exitValue() + " before " + url + " became reachable", lastError);
            }
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return;
                }
                lastError = new IOException("health endpoint returned HTTP " + response.code());
            } catch (IOException e) {
                lastError = e;
            }
            sleep(Duration.ofSeconds(1));
        }
        throw new AssertionError("Port-forward to " + what + " never became reachable at " + url, lastError);
    }

    private static void stopForward(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ── small helpers ──────────────────────────────────────────────────────────────────────────

    private static String bodyOf(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Credential-rotation wait was interrupted", e);
        }
    }

    private static String requiredConfig(String name) {
        String value = optionalConfig(name, null);
        if (value == null) {
            throw new IllegalStateException("Missing required property or environment variable: " + name);
        }
        return value;
    }

    private static String optionalConfig(String name, String fallback) {
        for (String candidate : List.of(name, name.replace('.', '_').toUpperCase(), "ENV_" + name)) {
            String value = System.getProperty(candidate);
            if (value == null || value.isBlank()) {
                value = System.getenv(candidate);
            }
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }
}
