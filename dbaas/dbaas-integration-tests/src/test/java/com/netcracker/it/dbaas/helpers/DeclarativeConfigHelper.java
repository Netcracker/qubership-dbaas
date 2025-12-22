package com.netcracker.it.dbaas.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.dbaas.entity.config.DatabaseDeclaration;
import com.netcracker.it.dbaas.entity.config.DeclarativeDatabaseCreationRequest;
import com.netcracker.it.dbaas.entity.config.DeclarativePayload;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.BASE_DECLARATIVE_SERVICE_URL;
import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.CONFIGS_PATH_V1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class DeclarativeConfigHelper {
    public final static RetryPolicy<Object> APPLY_CONFIGS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(5)).withMaxDuration(Duration.ofMinutes(3));
    private final DbaasHelperV3 helperV3;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeclarativeConfigHelper(DbaasHelperV3 helperV3) {
        this.helperV3 = helperV3;
    }

    public void applyDeclarativeConfig(DeclarativePayload declaration) throws IOException {
        applyDeclarativeConfigs(List.of(declaration));
    }

    public void applyDeclarativeConfigs(List<DeclarativePayload> declarations) throws IOException {
        List<String> trackingIds = new ArrayList<>();
        for (DeclarativePayload declaration : declarations) {
            try (Response response = helperV3.sendRequest(CONFIGS_PATH_V1 + "/apply", declaration, "POST")) {
                log.info("Apply response: {}", response);
                assertTrue(response.code() == 200 || response.code() == 202,
                        "Config apply should be successful");
                if (response.code() == 202) {
                    JsonNode node = objectMapper.readTree(response.body().string());
                    trackingIds.add(node.get("trackingId").asText());
                }
            }
        }
        for (String trackingId : trackingIds) {
            Failsafe.with(APPLY_CONFIGS_RETRY_POLICY).run(() -> {
                try (Response troubleshootingStatus = getApplyConfigTroubleshootingInfo(trackingId)) {
                    log.info("Status troubleshooting response: {}", troubleshootingStatus.body().string());
                }
                try (Response applyConfigStatus = getApplyConfigStatus(trackingId)) {
                    String body = applyConfigStatus.body().string();
                    log.info("Status response: {}", body);
                    assertEquals(200, applyConfigStatus.code());
                    JsonNode node = objectMapper.readTree(body);
                    assertEquals("COMPLETED", node.get("status").asText());
                }
            });
        }
    }

    public Response getApplyConfigStatus(String trackingId) throws IOException {
        Response response = helperV3.sendRequest(CONFIGS_PATH_V1 + "/operation/" + trackingId + "/status", null, "GET");
        log.info("Response: {}", response);
        return response;
    }

    public Response getApplyConfigTroubleshootingInfo(String trackingId) throws IOException {
        Response response = helperV3.sendRequest(CONFIGS_PATH_V1 + "/operation/" + trackingId + "/extendedTroubleshootingInfo", null, "GET");
        log.info("Response: {}", response);
        return response;
    }

    @Deprecated
    public Response awaitApplyConfigOld(String trackingId, int retries) throws IOException, InterruptedException {
        Response applyConfigStatus = getApplyConfigStatusOld(trackingId);
        int retry = 0;
        while (applyConfigStatus.code() == 202 && retry < retries) {
            applyConfigStatus = getApplyConfigStatusOld(trackingId);
            Thread.sleep(1000);
            retry++;
        }
        return applyConfigStatus;
    }

    @Deprecated
    public Response getApplyConfigStatusOld(String trackingId) throws IOException {
        try (Response response = helperV3.sendRequest(BASE_DECLARATIVE_SERVICE_URL + "/status/" + trackingId, null, "GET")) {
            log.info("Response: {}", response);
            return response;
        }
    }

    @Deprecated
    public DeclarativeDatabaseCreationRequest getDeclarativeDatabaseCreationRequest(Map<String, Object> classifier) {
        DeclarativeDatabaseCreationRequest databaseCreationRequest = new DeclarativeDatabaseCreationRequest();
        databaseCreationRequest.setApiVersion("v3");
        databaseCreationRequest.setKind("DatabaseDeclaration");
        List<DatabaseDeclaration> list = new ArrayList<>();
        DatabaseDeclaration config = new DatabaseDeclaration();
        config.setType("postgresql");

        DatabaseDeclaration.ClassifierConfig classifierConfig = new DatabaseDeclaration.ClassifierConfig();
        classifierConfig.setClassifier(classifier);
        config.setClassifierConfig(classifierConfig);
        DatabaseDeclaration.InitialInstantiation initialInstantiation = new DatabaseDeclaration.InitialInstantiation();
        initialInstantiation.setApproach("new");
        config.setInitialInstantiation(initialInstantiation);
        list.add(config);
        databaseCreationRequest.setDeclarations(list);
        return databaseCreationRequest;
    }
}
