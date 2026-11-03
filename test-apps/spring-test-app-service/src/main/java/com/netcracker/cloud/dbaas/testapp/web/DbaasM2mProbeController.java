package com.netcracker.cloud.dbaas.testapp.web;

import com.netcracker.cloud.dbaas.client.DbaasClient;
import com.netcracker.cloud.dbaas.client.DbaasClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/dbaas-m2m/probe")
    public ResponseEntity<Map<String, Object>> probe() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("authMode", System.getenv("M2M_AUTH_MODE"));
        probe.put("dbaasAddress", System.getenv("API_DBAAS_ADDRESS"));
        try {
            DbaasClient client = DbaasClientFactory.newDbaasClient();
            var identified = client.getPhysicalDatabases("postgresql").getIdentified();
            probe.put("status", "ok");
            probe.put("physicalDatabases", identified == null ? Map.of() : identified.keySet());
            return ResponseEntity.ok(probe);
        } catch (Exception e) {
            probe.put("status", "error");
            probe.put("reason", e.getClass().getName() + ": " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(probe);
        }
    }
}
