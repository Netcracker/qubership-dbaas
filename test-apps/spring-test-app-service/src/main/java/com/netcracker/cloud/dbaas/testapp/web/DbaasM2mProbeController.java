package com.netcracker.cloud.dbaas.testapp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.client.DbaasClientFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proves that {@code DbaasClientFactory} authorizes its own requests to DBaaS: the call carries the
 * pod's projected {@code dbaas} service account token, with no mounted secret, no Spring datasource
 * and no RestTemplate involved. Enabled only in the direct-M2M phase, so the mounted-secret and
 * REST-fallback phases keep running an untouched application.
 */
@RestController
@ConditionalOnProperty(name = "probe.dbaas-m2m.enabled", havingValue = "true")
public class DbaasM2mProbeController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PHYSICAL_DATABASES_PATH = "/api/v3/dbaas/postgresql/physical_databases";

    @GetMapping("/dbaas-m2m/probe")
    public ResponseEntity<Map<String, Object>> probe() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("authMode", System.getenv("M2M_AUTH_MODE"));
        probe.put("dbaasAddress", System.getenv("API_DBAAS_ADDRESS"));
        probe.put("clientJar", DbaasClientFactory.class.getProtectionDomain().getCodeSource().getLocation().toString());
        Map<String, Object> presented = new LinkedHashMap<>();
        try {
            var client = DbaasClientFactory.builder()
                    .httpClient(builder -> builder.addInterceptor(chain -> {
                        Request request = chain.request();
                        presented.putAll(presentedToken(request.header("Authorization")));
                        return chain.proceed(request);
                    }))
                    .build();
            var identified = client.getPhysicalDatabases("postgresql").getIdentified();
            probe.put("status", "ok");
            probe.put("physicalDatabases", identified == null ? Map.of() : identified.keySet());
            probe.put("presentedToken", presented);
            probe.put("controlRequests", unauthenticatedRequests());
            return ResponseEntity.ok(probe);
        } catch (Exception e) {
            probe.put("status", "error");
            probe.put("reason", e.getClass().getName() + ": " + e.getMessage());
            probe.put("presentedToken", presented);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(probe);
        }
    }

    /**
     * The same endpoint asked for without a credential DBaaS can accept. The probe only means
     * something if DBaaS refuses these: otherwise a 200 above would prove a token was sent, not that
     * DBaaS required it.
     */
    private static Map<String, Object> unauthenticatedRequests() {
        String url = System.getenv("API_DBAAS_ADDRESS") + PHYSICAL_DATABASES_PATH;
        OkHttpClient client = new OkHttpClient();
        return Map.of(
                "url", url,
                "withoutToken", statusOf(client, url, null),
                "withGarbageToken", statusOf(client, url, "Bearer not-a-kubernetes-token"));
    }

    private static Object statusOf(OkHttpClient client, String url, String authorization) {
        Request.Builder request = new Request.Builder().url(url);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (Response response = client.newCall(request.build()).execute()) {
            return response.code();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * The claims of the token the client actually put on the wire, so a passing probe also proves
     * which identity DBaaS accepted. Never returns the token itself.
     */
    private static Map<String, Object> presentedToken(String authorization) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (authorization == null) {
            facts.put("header", "missing");
            return facts;
        }
        facts.put("scheme", authorization.substring(0, authorization.indexOf(' ') + 1).trim());
        String[] segments = authorization.substring(authorization.indexOf(' ') + 1).split("\\.");
        if (segments.length < 2) {
            facts.put("claims", "not a jwt");
            return facts;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(pad(segments[1])), StandardCharsets.UTF_8);
            Map<?, ?> claims = JSON.readValue(payload, Map.class);
            for (String claim : new String[]{"iss", "sub", "aud", "exp", "nbf"}) {
                if (claims.containsKey(claim)) {
                    facts.put(claim, claims.get(claim));
                }
            }
        } catch (Exception e) {
            facts.put("claims", "unreadable: " + e.getClass().getSimpleName());
        }
        return facts;
    }

    private static String pad(String segment) {
        return switch (segment.length() % 4) {
            case 2 -> segment + "==";
            case 3 -> segment + "=";
            default -> segment;
        };
    }
}
