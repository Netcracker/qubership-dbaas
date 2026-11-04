package com.netcracker.it.testapps.springtestapp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * dbaas-client-light authorizes its own requests to DBaaS. The sample service builds a client through
 * DbaasClientFactory and reports the address and the credential it used, and each mode is checked against what that
 * mode owes: a legacy request routed to dbaas-agent without a Kubernetes identity, a Kubernetes request carrying the
 * token this pod was projected for the {@code dbaas} audience straight to DBaaS.
 */
class SpringTestAppLightClientM2MIT {

    private static final String SAMPLE_SERVICE = "spring-test-app-service";
    private static final String DBAAS_SERVICE = "dbaas-aggregator";
    private static final String REPORT_PATH = "/dbaas-m2m/light-client";
    private static final int HTTP_PORT = 8080;

    private static final Gson GSON = new Gson();
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient();
    private static final KubernetesClient KUBERNETES_CLIENT = new KubernetesClientBuilder().build();

    private static String namespace;
    private static LocalPortForward sampleService;
    private static JsonObject report;
    private static String requestPath;

    @BeforeAll
    static void readLightClientReport() throws IOException {
        namespace = requiredPropertyOrEnv("clouds.cloud.namespaces.namespace");
        sampleService = forward(SAMPLE_SERVICE);
        String reportUrl = "http://127.0.0.1:" + sampleService.getLocalPort() + REPORT_PATH;
        awaitReachable(reportUrl);
        report = GSON.fromJson(execute(get(reportUrl, null), 200), JsonObject.class);
        assertEquals("ok", text(report, "status"), "the light client failed to call DBaaS: " + report);
        requestPath = URI.create(text(report, "requestUrl")).getPath();
    }

    @AfterAll
    static void stopForwarding() throws IOException {
        if (sampleService != null) {
            sampleService.close();
        }
    }

    @Test
    void legacyLightClientRoutesItsRequestToDbaasAgent() {
        assumeTrue(isLegacy(), "the sample service sends a Kubernetes token, not a legacy one");
        String target = text(report, "target");
        assertEquals(text(report, "agentAddress"), target, "a legacy request must be addressed to dbaas-agent: " + report);
        assertTrue(text(report, "requestUrl").startsWith(target), "the client must send its requests to dbaas-agent: " + report);
        assertEquals("Bearer", text(report, "authScheme"), "the client must authorize its request: " + report);
        assertFalse(reported("presentsProjectedToken"), "a legacy request carries no Kubernetes token: " + report);
    }

    @Test
    void kubernetesLightClientPresentsPodProjectedTokenToDbaas() {
        assumeTrue(!isLegacy(), "the sample service sends a legacy token, not a Kubernetes one");
        String target = text(report, "target");
        assertNotEquals(text(report, "agentAddress"), target,
                "a request carrying a Kubernetes token must not be routed through dbaas-agent: " + report);
        assertTrue(text(report, "requestUrl").startsWith(target),
                "the client must send its requests to the DBaaS address it resolved: " + report);
        assertEquals("Bearer", text(report, "authScheme"), "the client must authorize its request: " + report);
        assertTrue(reported("presentsProjectedToken"),
                "the client must present the token this pod was projected for DBaaS: " + report);
    }

    @Test
    void dbaasRefusesTheSameRequestWithoutThatToken() throws IOException {
        try (LocalPortForward dbaas = forward(DBAAS_SERVICE)) {
            String url = "http://127.0.0.1:" + dbaas.getLocalPort() + requestPath;
            awaitReachable(url);
            assertEquals(401, status(get(url, null)),
                    "DBaaS must refuse the endpoint the light client called, or the test proves nothing");
            assertEquals(401, status(get(url, "Bearer not-a-kubernetes-token")), "DBaaS must refuse a token it cannot verify");
        }
    }

    private static boolean isLegacy() {
        return "LEGACY".equals(text(report, "authMode"));
    }

    private static LocalPortForward forward(String service) {
        return KUBERNETES_CLIENT.services().inNamespace(namespace).withName(service).portForward(HTTP_PORT);
    }

    /**
     * A port-forward refuses connections for a moment after it is opened.
     */
    private static void awaitReachable(String url) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try {
                status(get(url, null));
                return;
            } catch (IOException e) {
                sleep(1);
            }
        }
        throw new AssertionError("No answer from " + url);
    }

    private static String execute(Request request, int expectedStatus) throws IOException {
        try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody payload = response.body();
            String body = payload == null ? "" : payload.string();
            assertEquals(expectedStatus, response.code(), "unexpected response from " + request.url() + ": " + body);
            return body;
        }
    }

    private static int status(Request request) throws IOException {
        try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
            return response.code();
        }
    }

    private static Request get(String url, String authorization) {
        Request.Builder request = new Request.Builder().url(url).get();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return request.build();
    }

    private static String text(JsonObject object, String member) {
        return object.has(member) && !object.get(member).isJsonNull()
                ? object.get(member).getAsString()
                : "<" + member + " missing>";
    }

    private static boolean reported(String field) {
        return report.get(field).getAsBoolean();
    }

    private static String requiredPropertyOrEnv(String name) {
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

    private static void sleep(int seconds) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
