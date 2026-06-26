package com.netcracker.cloud.dbaas.testapp.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness/readiness endpoint. Intentionally does not touch the database so the pod can
 * become Ready before the lazy datasource resolves its first connection.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
