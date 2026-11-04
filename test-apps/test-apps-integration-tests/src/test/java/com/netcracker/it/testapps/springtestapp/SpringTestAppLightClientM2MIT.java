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

    private static LocalPortForward sampleService;
    private static JsonObject report;
    private static String requestPath;

    @BeforeAll
    static void readLightClientReport() throws IOException {
        sampleService = ClusterHelper.toService(SAMPLE_SERVICE, HTTP_PORT);
        String reportUrl = ClusterHelper.url(sampleService, REPORT_PATH);
        ClusterHelper.awaitReachable(reportUrl, SAMPLE_SERVICE);
        report = GSON.fromJson(ClusterHelper.execute(get(reportUrl, null), 200), JsonObject.class);
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
