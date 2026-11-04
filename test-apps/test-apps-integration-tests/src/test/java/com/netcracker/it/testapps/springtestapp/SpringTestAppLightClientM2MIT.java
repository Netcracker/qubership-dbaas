package com.netcracker.it.testapps.springtestapp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.netcracker.it.testapps.helpers.ClusterHelper;
import io.fabric8.kubernetes.client.LocalPortForward;
import okhttp3.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * dbaas-client-light authorizes its own requests to DBaaS. The sample service builds a client through
 * DbaasClientFactory and reports the address, the credential and the answer it got, and each combination is checked
 * against what it owes: a legacy request routed to dbaas-agent without a Kubernetes identity, a Kubernetes request
 * carrying the token this pod was projected for the {@code dbaas} audience straight to DBaaS, and that request refused
 * when the cluster runs in legacy mode, where DBaaS accepts no Kubernetes token - a refusal the client answers through
 * dbaas-agent rather than by failing.
 */
class SpringTestAppLightClientM2MIT {

    private static final String SAMPLE_SERVICE = "spring-test-app-service";
    private static final String DBAAS_SERVICE = "dbaas-aggregator";
    private static final String REPORT_PATH = "/dbaas-m2m/light-client";
    private static final int HTTP_PORT = 8080;

    private static final Gson GSON = new Gson();
    private static final String SERVER_M2M_AUTH_MODE = ClusterHelper.config("dbaas.server.m2m.auth.mode", "hybrid");

    private static LocalPortForward sampleService;
    private static JsonObject report;

    @BeforeAll
    static void readLightClientReport() throws IOException {
        sampleService = ClusterHelper.toService(SAMPLE_SERVICE, HTTP_PORT);
        String reportUrl = ClusterHelper.url(sampleService, REPORT_PATH);
        ClusterHelper.awaitReachable(reportUrl, SAMPLE_SERVICE);
        report = GSON.fromJson(ClusterHelper.execute(get(reportUrl, null), 200), JsonObject.class);
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
        assertEquals("ok", text(report, "status"), "DBaaS must answer a legacy request in any mode: " + report);
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
    void dbaasAnswersTheLightClientWhileItAcceptsKubernetesTokens() {
        assumeTrue(!isLegacy() && serverAcceptsKubernetesTokens(),
                "only a request with a Kubernetes token, and only where DBaaS accepts one");
        assertEquals(200, dbaasStatus(), "DBaaS accepts the projected token in this mode: " + report);
        assertEquals("ok", text(report, "status"), "the light client call must succeed: " + report);
    }

    @Test
    void dbaasRefusesTheProjectedTokenAndTheClientFallsBackToItsAgent() {
        assumeTrue(!isLegacy() && !serverAcceptsKubernetesTokens(),
                "only a request with a Kubernetes token, and only where DBaaS refuses one");
        assertTrue(reported("presentsProjectedToken"),
                "the client must be refused for presenting the projected token, not for sending nothing: " + report);
        assertEquals(401, dbaasStatus(), "a legacy DBaaS must refuse a Kubernetes token: " + report);
        assertEquals("ok", text(report, "status"),
                "a hybrid client answers itself through its legacy path once DBaaS refuses the token: " + report);
        assertTrue(text(report, "fallbackUrl").startsWith(text(report, "agentAddress")),
                "the fallback must go through dbaas-agent, not back to the DBaaS address that refused it: " + report);
    }

    @Test
    void dbaasRefusesTheSameRequestWithoutThatToken() throws IOException {
        String requestPath = URI.create(text(report, "requestUrl")).getPath();
        try (LocalPortForward dbaas = ClusterHelper.toService(DBAAS_SERVICE, HTTP_PORT)) {
            String url = ClusterHelper.url(dbaas, requestPath);
            ClusterHelper.awaitReachable(url, DBAAS_SERVICE);
            assertEquals(401, ClusterHelper.status(get(url, null)),
                    "DBaaS must refuse the endpoint the light client called, or the test proves nothing");
            assertEquals(401, ClusterHelper.status(get(url, "Bearer not-a-kubernetes-token")), "DBaaS must refuse a token it cannot verify");
        }
    }

    private static boolean isLegacy() {
        return "LEGACY".equals(text(report, "authMode"));
    }

    private static boolean serverAcceptsKubernetesTokens() {
        return !"legacy".equalsIgnoreCase(SERVER_M2M_AUTH_MODE);
    }

    private static int dbaasStatus() {
        return report.get("dbaasStatus").getAsInt();
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

}
