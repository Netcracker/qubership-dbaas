package com.netcracker.cloud.dbaas.testapp.web;

import com.netcracker.cloud.dbaas.client.DbaasClientFactory;
import com.netcracker.cloud.dbaas.client.config.DbaasApiAddress;
import com.netcracker.cloud.security.core.utils.k8s.AudienceName;
import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import com.netcracker.cloud.security.core.utils.k8s.impl.CachingTokenSource;
import okhttp3.Request;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports the address and the credential a {@link DbaasClientFactory} client put on the wire, for the sample service ITs.
 */
@RestController
public class DbaasLightClientController {

    private static final String DATABASE_TYPE = "postgresql";
    private static final Path PROJECTED_DBAAS_TOKEN =
            CachingTokenSource.TOKENS_DIR_DEFAULT.resolve(AudienceName.DBAAS).resolve("token");

    @GetMapping("/dbaas-m2m/light-client")
    public ResponseEntity<Map<String, Object>> lightClientReport() {
        M2MAuthMode authMode = M2MAuthMode.readFromEnv();
        DbaasApiAddress resolved = DbaasApiAddress.resolve(authMode);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("authMode", authMode.name());
        report.put("target", resolved.url());
        report.put("agentAddress", resolved.agentUrl());
        try {
            DbaasClientFactory.builder()
                    .httpClient(builder -> builder.addInterceptor(chain -> {
                        Request request = chain.request();
                        String authorization = request.header("Authorization");
                        report.put("requestUrl", request.url().toString());
                        report.put("authScheme", schemeOf(authorization));
                        report.put("presentsProjectedToken", presentsProjectedToken(authorization));
                        return chain.proceed(request);
                    }))
                    .build()
                    .getPhysicalDatabases(DATABASE_TYPE);
            report.put("status", "ok");
        } catch (Exception e) {
            report.put("status", "error");
            report.put("reason", e.getClass().getName() + ": " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(report);
        }
        return ResponseEntity.ok(report);
    }

    private static String schemeOf(String authorization) {
        if (authorization == null) {
            return "missing";
        }
        int separator = authorization.indexOf(' ');
        return separator < 0 ? authorization : authorization.substring(0, separator);
    }

    private static boolean presentsProjectedToken(String authorization) {
        if (authorization == null) {
            return false;
        }
        int separator = authorization.indexOf(' ');
        String presented = separator < 0 ? authorization : authorization.substring(separator + 1);
        return !presented.isEmpty() && presented.equals(projectedDbaasToken());
    }

    private static String projectedDbaasToken() {
        try {
            return Files.readString(PROJECTED_DBAAS_TOKEN).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
