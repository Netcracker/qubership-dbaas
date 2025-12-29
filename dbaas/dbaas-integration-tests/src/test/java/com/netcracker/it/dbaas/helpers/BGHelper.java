package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.BgNamespaceRequest;
import com.netcracker.it.dbaas.entity.BgStateRequest;
import com.netcracker.it.dbaas.entity.DeleteOrphansRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

@Slf4j
public class BGHelper {
    public static final String TEST_NAMESPACE_CONTROLLER = "controller-test-namespace";
    public static final String DBAAS_BLUE_GREEN_PATH_V1 = "/api/bluegreen/v1/operation";

    public static final String IDLE_STATE = "idle";
    public static final String ACTIVE_STATE = "active";
    public static final String LEGACY_STATE = "legacy";
    public static final String CANDIDATE_STATE = "candidate";
    private final DbaasHelperV3 dhv3;

    public BGHelper(DbaasHelperV3 dhv3) {
        this.dhv3 = dhv3;
    }

    public Response initDomain(String activeNamespace, String idleNamespace) throws IOException {
        BgStateRequest bgStateRequest = createActiveAndIdleBgStateRequestBody(activeNamespace, idleNamespace);

        return initDomain(bgStateRequest);
    }

    public Response warmupDomain(String activeNamespace, String candidateNamespace) throws IOException {
        BgStateRequest bgStateRequest = createWarmupBgStateRequestBody(activeNamespace, candidateNamespace);
        return warmupDomain(bgStateRequest);
    }

    public Response promoteDomain(String legacyNamespace, String activeNamespace) throws IOException {
        BgStateRequest bgStateRequest = createPromoteBgStateRequestBody(legacyNamespace, activeNamespace);
        return promoteDomain(bgStateRequest);
    }

    public Response rollbackDomain(String activeNamespace, String candidateNamespace) throws IOException {
        BgStateRequest bgStateRequest = createRollbackBgStateRequestBody(activeNamespace, candidateNamespace);
        return rollbackDomain(bgStateRequest);
    }

    public Response commitDomain(String activeNamespace, String idleNamespace) throws IOException {
        BgStateRequest bgStateRequest = createActiveAndIdleBgStateRequestBody(activeNamespace, idleNamespace);

        return commitDomain(bgStateRequest);
    }

    public BgStateRequest createActiveAndIdleBgStateRequestBody(String activeNamespace, String idleNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceIdle = createBgStateNamespace(IDLE_STATE, idleNamespace, null);

        return getBgStateRequest(bgNamespaceActive, bgNamespaceIdle);
    }

    public Response cleanupOrphans(List<String> namespaces, boolean delete) throws IOException {
        DeleteOrphansRequest deleteOrphansRequest = createCleanupOrphansRequestBody(namespaces, delete);

        return cleanupOrphans(deleteOrphansRequest);
    }

    private BgStateRequest createWarmupBgStateRequestBody(String activeNamespace, String candidateNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, candidateNamespace, "v2");

        return getBgStateRequest(bgNamespaceActive, bgNamespaceCandidate);
    }

    private BgStateRequest createPromoteBgStateRequestBody(String legacyNamespace, String activeNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(LEGACY_STATE, legacyNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceCandidate = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v2");

        return getBgStateRequest(bgNamespaceActive, bgNamespaceCandidate);
    }

    private BgStateRequest createRollbackBgStateRequestBody(String activeNamespace, String candidateNamespace) {
        BgStateRequest.BGStateNamespace bgNamespaceActive = createBgStateNamespace(ACTIVE_STATE, activeNamespace, "v1");
        BgStateRequest.BGStateNamespace bgNamespaceCandidate = createBgStateNamespace(CANDIDATE_STATE, candidateNamespace, "v2");

        return getBgStateRequest(bgNamespaceActive, bgNamespaceCandidate);
    }

    private DeleteOrphansRequest createCleanupOrphansRequestBody(List<String> namespaces, boolean delete) {
        DeleteOrphansRequest deleteOrphansRequest = new DeleteOrphansRequest();
        deleteOrphansRequest.setNamespaces(namespaces);
        deleteOrphansRequest.setDelete(delete);
        return deleteOrphansRequest;
    }

    @NotNull
    private BgStateRequest getBgStateRequest(BgStateRequest.BGStateNamespace bgNamespaceActive, BgStateRequest.BGStateNamespace bgNamespaceIdle) {
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(bgNamespaceActive);
        bgState.setPeerNamespace(bgNamespaceIdle);
        bgState.setControllerNamespace(TEST_NAMESPACE_CONTROLLER);

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
