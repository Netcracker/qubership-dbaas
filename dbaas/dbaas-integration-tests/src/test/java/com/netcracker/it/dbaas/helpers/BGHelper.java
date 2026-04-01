package com.netcracker.it.dbaas.helpers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.dbaas.entity.BgDomainForList;
import com.netcracker.it.dbaas.entity.BgNamespaceRequest;
import com.netcracker.it.dbaas.entity.BgStateRequest;
import com.netcracker.it.dbaas.entity.DeleteOrphansRequest;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static com.netcracker.it.dbaas.helpers.DbaasHelperV3.TEST_NAMESPACE_PATTERN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Slf4j
public class BGHelper {
    public static final String TEST_NAMESPACE_ACTIVE = "active-test-namespace";
    public static final String TEST_NAMESPACE_CANDIDATE = "candidate-test-namespace";
    public static final String TEST_NAMESPACE_CONTROLLER = "controller-test-namespace";
    public static final String DBAAS_BLUE_GREEN_PATH_V1 = "/api/bluegreen/v1/operation";

    public static final String IDLE_STATE = "idle";
    public static final String ACTIVE_STATE = "active";
    public static final String LEGACY_STATE = "legacy";
    public static final String CANDIDATE_STATE = "candidate";
    private final DbaasHelperV3 dhv3;

    public final static RetryPolicy<Object> OPERATION_STATUS_RETRY_POLICY = new RetryPolicy<>()
            .withMaxRetries(-1).withDelay(Duration.ofSeconds(1)).withMaxDuration(Duration.ofMinutes(1));
    public ObjectMapper objectMapper = new ObjectMapper();

    public BGHelper(DbaasHelperV3 dhv3) {
        this.dhv3 = dhv3;
    }

    public String doWarmup(String activeNamespace, String candidateNamespace, String controllerNamespace) throws IOException {
        String trackingId;
        try (Response warmupResponse = warmupDomain(activeNamespace, candidateNamespace, controllerNamespace)) {
            Assertions.assertEquals(202, warmupResponse.code());
            JsonNode node = objectMapper.readTree(warmupResponse.body().string());
            trackingId = node.get("trackingId").asText();
        }
        Failsafe.with(OPERATION_STATUS_RETRY_POLICY.copy().withMaxDuration(Duration.ofMinutes(3))).run(() -> {
            try (Response operationStatus = getOperationStatus(trackingId)) {
                JsonNode node = objectMapper.readTree(operationStatus.body().string());
                assertFalse(node.get("status").asText().equals("in progress") || node.get("status").asText().equals("not started"));
            }
        });
        return trackingId;
    }

    public void destroyAllDomains() throws IOException {
        try (Response response = listDomains()) {
            Assertions.assertEquals(200, response.code());
            String responseBody = response.body().string();
            log.info("List domains response: {}", responseBody);
            List<BgDomainForList> domains = objectMapper.readValue(responseBody, new TypeReference<List<BgDomainForList>>() {
            }).stream().filter(domain ->
                    TEST_NAMESPACE_PATTERN.matcher(domain.getOriginNamespace()).matches()
                            && TEST_NAMESPACE_PATTERN.matcher(domain.getPeerNamespace()).matches()
            ).toList();

            for (BgDomainForList bgDomainForList : domains) {
                try (Response destroyResponse = destroyDomain(new BgNamespaceRequest(bgDomainForList.getOriginNamespace(), bgDomainForList.getPeerNamespace())) ) {
                    assertEquals(200, destroyResponse.code(),
                            String.format("Couldn't destroy domain: originNamespace=%s,  peerNamespace=%s", bgDomainForList.getOriginNamespace(), bgDomainForList.getPeerNamespace()));
                }
            }
        }
    }



    public Response initDomain(String activeNamespace, String idleNamespace, String controllerNamespace) throws IOException {
        BgStateRequest bgStateRequest = createActiveAndIdleBgStateRequestBody(activeNamespace, idleNamespace, controllerNamespace);

        return initDomain(bgStateRequest);
    }

    public Response warmupDomain(String activeNamespace, String candidateNamespace, String controllerNamespace) throws IOException {
        BgStateRequest bgStateRequest = createWarmupBgStateRequestBody(activeNamespace, candidateNamespace, controllerNamespace);
        return warmupDomain(bgStateRequest);
    }

    public Response promoteDomain(String legacyNamespace, String activeNamespace, String controllerNamespace) throws IOException {
        BgStateRequest bgStateRequest = createPromoteBgStateRequestBody(legacyNamespace, activeNamespace, controllerNamespace);
        return promoteDomain(bgStateRequest);
    }

    public Response rollbackDomain(String activeNamespace, String candidateNamespace, String controllerNamespace) throws IOException {
        BgStateRequest bgStateRequest = createRollbackBgStateRequestBody(activeNamespace, candidateNamespace, controllerNamespace);
        return rollbackDomain(bgStateRequest);
    }

    public Response commitDomain(String activeNamespace, String idleNamespace, String controllerNamespace) throws IOException {
        BgStateRequest bgStateRequest = createActiveAndIdleBgStateRequestBody(activeNamespace, idleNamespace, controllerNamespace);

        return commitDomain(bgStateRequest);
    }

    public BgStateRequest createActiveAndIdleBgStateRequestBody(String activeNamespace, String idleNamespace, String controllerNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceIdle = createBgStateNamespace(IDLE_STATE, idleNamespace, null);

        return getBgStateRequest(bgNamespaceActive, bgNamespaceIdle, controllerNamespace);
    }

    public Response cleanupOrphans(List<String> namespaces, boolean delete) throws IOException {
        DeleteOrphansRequest deleteOrphansRequest = createCleanupOrphansRequestBody(namespaces, delete);

        return cleanupOrphans(deleteOrphansRequest);
    }

    private BgStateRequest createWarmupBgStateRequestBody(String activeNamespace, String candidateNamespace, String controllerNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, candidateNamespace, "v2");

        return getBgStateRequest(bgNamespaceActive, bgNamespaceCandidate, controllerNamespace);
    }

    private BgStateRequest createPromoteBgStateRequestBody(String legacyNamespace, String activeNamespace, String controllerNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(LEGACY_STATE, legacyNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceCandidate = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v2");

        return getBgStateRequest(bgNamespaceActive, bgNamespaceCandidate, controllerNamespace);
    }

    private BgStateRequest createRollbackBgStateRequestBody(String activeNamespace, String candidateNamespace, String controllerNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, candidateNamespace, "v2");

        return getBgStateRequest(bgNamespaceActive, bgNamespaceCandidate, controllerNamespace);
    }

    private DeleteOrphansRequest createCleanupOrphansRequestBody(List<String> namespaces, boolean delete) {
        DeleteOrphansRequest deleteOrphansRequest = new DeleteOrphansRequest();
        deleteOrphansRequest.setNamespaces(namespaces);
        deleteOrphansRequest.setDelete(delete);
        return deleteOrphansRequest;
    }

    @NotNull
    private BgStateRequest getBgStateRequest(BgStateRequest.BGStateNamespace bgNamespaceActive, BgStateRequest.BGStateNamespace bgNamespaceIdle, String controllerNamespace) {
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgNamespaceActive);
        bgState.setPeerNamespace(bgNamespaceIdle);
        bgState.setControllerNamespace(controllerNamespace);

        bgStateRequest.setBGState(bgState);
        return bgStateRequest;
    }

    private static BgStateRequest.BGStateNamespace createBgStateNamespace(String state, String namespace, String version) {
        BgStateRequest.BGStateNamespace bgNamespace = new BgStateRequest.BGStateNamespace();
        bgNamespace.setState(state);
        bgNamespace.setName(namespace);
        bgNamespace.setVersion(version);
        return bgNamespace;
    }

    private Response getDomain(String namespace) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/get-domains/" + namespace, null, "GET");
    }

    public Response warmupDomain(BgStateRequest bgStateRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/warmup", bgStateRequest, "POST");
    }

    public Response promoteDomain(BgStateRequest bgStateRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/promote", bgStateRequest, "POST");
    }

    public Response rollbackDomain(BgStateRequest bgStateRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/rollback", bgStateRequest, "POST");
    }

    public Response commitDomain(BgStateRequest bgStateRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/commit", bgStateRequest, "POST");
    }

    public Response initDomain(BgStateRequest bgStateRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/init-domain", bgStateRequest, "POST");
    }

    public Response listDomains() throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/list-domains", null, "GET");
    }

    public Response destroyDomain(BgNamespaceRequest bgStateRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/destroy-domain", bgStateRequest, "DELETE");
    }

    public Response getOrphans(List<String> namespaces) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/orphans", namespaces, "POST");
    }

    public Response cleanupOrphans(DeleteOrphansRequest deleteOrphansRequest) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/orphans", deleteOrphansRequest, "DELETE");
    }

    public Response getOperationStatus(String trackingId) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/" + trackingId + "/status", null, "GET");
    }

    public Response terminateOperation(String trackingId) throws IOException {
        return dhv3.sendRequest(DBAAS_BLUE_GREEN_PATH_V1 + "/" + trackingId + "/terminate", "", "POST");
    }
}
