package org.qubership.cloud.dbaas.controller;

import org.qubership.cloud.dbaas.dto.BulkDatabaseCreateResponse;
import org.qubership.cloud.dbaas.dto.bluegreen.*;
import org.qubership.cloud.dbaas.entity.pg.*;
import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import org.qubership.cloud.dbaas.exceptions.BgRequestValidationException;
import org.qubership.cloud.dbaas.exceptions.ForbiddenDeleteOperationException;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.cloud.dbaas.service.DbaaSHelper;
import org.qubership.cloud.dbaas.service.ProcessService;
import org.qubership.cloud.dbaas.service.processengine.tasks.BackupDatabaseTask;
import org.qubership.cloud.dbaas.service.processengine.tasks.DeleteBackupTask;
import org.qubership.cloud.dbaas.service.processengine.tasks.NewDatabaseTask;
import org.qubership.cloud.dbaas.service.processengine.tasks.RestoreDatabaseTask;
import org.qubership.core.scheduler.po.DataContext;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.*;
import static org.qubership.cloud.dbaas.service.processengine.Const.UPDATE_BG_STATE_TASK;
import static jakarta.ws.rs.core.Response.Status.ACCEPTED;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
@Slf4j
class BlueGreenControllerV1Test {

    private static BlueGreenControllerV1 blueGreenControllerV1;
    private static BlueGreenService blueGreenService;

    private static DbaaSHelper dbaaSHelper;

    private static ProcessService processService;

    @BeforeAll
    static void setup() {
        blueGreenControllerV1 = new BlueGreenControllerV1();
        blueGreenService = Mockito.mock(BlueGreenService.class);
        blueGreenControllerV1.blueGreenService = blueGreenService;
        processService = Mockito.mock(ProcessService.class);
        blueGreenControllerV1.processService = processService;
        dbaaSHelper = Mockito.mock(DbaaSHelper.class);
        blueGreenControllerV1.dbaaSHelper = dbaaSHelper;
    }

    @Test
    void warmupOK() {
        BulkDatabaseCreateResponse bulkDatabaseCreateResponse = new BulkDatabaseCreateResponse();
        bulkDatabaseCreateResponse.setCreationStatus(OK);
        List<BulkDatabaseCreateResponse> warmupResult = Arrays.asList(bulkDatabaseCreateResponse);
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "test-namespace1", "v1"),
                createBgStateNamespace(CANDIDATE_STATE, "test-namespace", "v2"));
        ProcessInstanceImpl processInstance = Mockito.mock(ProcessInstanceImpl.class);
        when(processInstance.getId()).thenReturn(UUID.randomUUID().toString());
        when(processInstance.getTasks()).thenReturn(Collections.emptyList());

        when(blueGreenService.warmup(bgStateRequest.getBGState())).thenReturn(processInstance);
        Response warmupResponse = blueGreenControllerV1.warmup(bgStateRequest);
        Assertions.assertEquals(ACCEPTED.getStatusCode(), warmupResponse.getStatus());
    }

    @Test
    void warmupValidationException() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "test-namespace1", "v1"),
                createBgStateNamespace(CANDIDATE_STATE, "test-namespace", "v2"));
        bgStateRequest.getBGState().setOriginNamespace(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            blueGreenControllerV1.warmup(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("Origin namespace or peer namespace in not present"));
    }

    @Test
    void testCommit() {
        BgStateRequest bgStateRequestCommit = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));

        BgDomain bgDomain = new BgDomain();
        bgDomain.setId(UUID.randomUUID());

        BgNamespace pNamespace = createBgNamespace("origin-namespace", ACTIVE_STATE, "v1");
        BgNamespace sNamespace = createBgNamespace("peer-namespace", CANDIDATE_STATE, "v2");
        bgDomain.setNamespaces(List.of(pNamespace, sNamespace));

        when(blueGreenService.getDomain("origin-namespace")).thenReturn(bgDomain);

        Response commitResponse = blueGreenControllerV1.commit(bgStateRequestCommit);
        assertEquals(200, commitResponse.getStatus());
        assertEquals("0 databases are marked as Orphan", commitResponse.readEntity(BlueGreenResponse.class).getMessage());
    }

    @Test
    void testAlreadyCommitted() {
        BgStateRequest bgStateRequestCommit = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));

        BgDomain bgDomain = new BgDomain();
        bgDomain.setId(UUID.randomUUID());

        BgNamespace pNamespace = createBgNamespace("origin-namespace", ACTIVE_STATE, "v1");
        BgNamespace sNamespace = createBgNamespace("peer-namespace", IDLE_STATE);
        bgDomain.setNamespaces(List.of(pNamespace, sNamespace));

        when(blueGreenService.getDomain("origin-namespace")).thenReturn(bgDomain);

        Response commitResponse = blueGreenControllerV1.commit(bgStateRequestCommit);
        assertEquals(200, commitResponse.getStatus());
    }

    @Test
    void testCommitIncorrectStates() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "test-namespace1", "v1"),
                createBgStateNamespace(CANDIDATE_STATE, "test-namespace", "v2"));

        BgRequestValidationException exception = assertThrows(BgRequestValidationException.class, () -> {
            blueGreenControllerV1.commit(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("States of bgRequest must be active and idle"));
    }

    @Test
    void testCommitIncorrectDomain() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));

        when(blueGreenService.getDomain("origin-namespace")).thenReturn(null);

        BgRequestValidationException exception = assertThrows(BgRequestValidationException.class, () -> {
            blueGreenControllerV1.commit(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("Can't find registered Blue-Green domain with requested namespace"));
    }

    @Test
    void testCommitIncorrectNamespace() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));

        BgDomain bgDomain = new BgDomain();
        bgDomain.setId(UUID.randomUUID());

        BgNamespace pNamespace = createBgNamespace("origin-namespace", ACTIVE_STATE, "v1");
        BgNamespace sNamespace = createBgNamespace("not-peer-namespace", CANDIDATE_STATE, "v2");
        bgDomain.setNamespaces(List.of(pNamespace, sNamespace));

        when(blueGreenService.getDomain("origin-namespace")).thenReturn(bgDomain);

        BgRequestValidationException exception = assertThrows(BgRequestValidationException.class, () -> {
            blueGreenControllerV1.commit(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("Request with incorrect namespaces"));
    }

    @Test
    void testCommitDomainWithoutActiveState() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));

        BgDomain bgDomain = new BgDomain();
        bgDomain.setId(UUID.randomUUID());

        BgNamespace pNamespace = createBgNamespace("origin-namespace", "dif_state", "v1");
        BgNamespace sNamespace = createBgNamespace("peer-namespace", CANDIDATE_STATE, "v2");
        bgDomain.setNamespaces(List.of(pNamespace, sNamespace));

        when(blueGreenService.getDomain("origin-namespace")).thenReturn(bgDomain);

        BgRequestValidationException exception = assertThrows(BgRequestValidationException.class, () -> {
            blueGreenControllerV1.commit(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("Blue-Green domain doesn't contain active state"));
    }

    @Test
    void testCommitIncorrectVersion() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));

        BgDomain bgDomain = new BgDomain();
        bgDomain.setId(UUID.randomUUID());

        BgNamespace pNamespace = createBgNamespace("origin-namespace", ACTIVE_STATE, "v2");
        BgNamespace sNamespace = createBgNamespace("peer-namespace", CANDIDATE_STATE, "v3");
        bgDomain.setNamespaces(List.of(pNamespace, sNamespace));

        when(blueGreenService.getDomain("origin-namespace")).thenReturn(bgDomain);

        BgRequestValidationException exception = assertThrows(BgRequestValidationException.class, () -> {
            blueGreenControllerV1.commit(bgStateRequest);
        });
        Assertions.assertTrue(exception.getMessage().contains("Incorrect version for active namespace"));
    }

    @Test
    void testInitDomain() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(IDLE_STATE, "peer-namespace", null));
        doNothing().when(blueGreenService).initBgDomain(bgStateRequest);
        blueGreenControllerV1.initBgDomain(bgStateRequest);
        verify(blueGreenService).initBgDomain(bgStateRequest);
    }

    @Test
    void testPromote() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(LEGACY_STATE, "peer-namespace", "v2"));
        Response promoteResponse = blueGreenControllerV1.promote(bgStateRequest);
        assertEquals(200, promoteResponse.getStatus());
    }

    @Test
    void testDestroyBgDomainException() {
        BgNamespaceRequest bgNamespaceRequest = new BgNamespaceRequest("origin-namespace", "peer-namespace");
        when(dbaaSHelper.isProductionMode()).thenReturn(true);
        Assertions.assertThrows(ForbiddenDeleteOperationException.class, () -> blueGreenControllerV1.destroyBgDomain(bgNamespaceRequest));
    }

    @Test
    void testDestroyBgDomain() {
        BgNamespaceRequest bgNamespaceRequest = new BgNamespaceRequest("origin-namespace", "peer-namespace");
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        blueGreenControllerV1.destroyBgDomain(bgNamespaceRequest);
        verify(blueGreenService).destroyDomain(any());
    }


    @Test
    void testRollback() {
        BgStateRequest bgStateRequest = getBgStateRequest(createBgStateNamespace(ACTIVE_STATE, "origin-namespace", "v1"),
                createBgStateNamespace(CANDIDATE_STATE, "peer-namespace", "v2"));
        Response promoteResponse = blueGreenControllerV1.rollback(bgStateRequest);
        assertEquals(200, promoteResponse.getStatus());
    }

    @Test
    void testGetOperationStatus() {
        ProcessInstanceImpl testProcessInstance = createTestProcessInstance();
        String trackingId = "trackingId";
        when(processService.getProcess(trackingId)).thenReturn(testProcessInstance);
        Response operationStatus = blueGreenControllerV1.getOperationStatus(trackingId);
        assertEquals(200, operationStatus.getStatus());
        assertEquals("0 of 2 databases are processed", operationStatus.readEntity(OperationStatusResponse.class).getMessage());
        Assertions.assertNotNull(operationStatus.readEntity(OperationStatusResponse.class).getOperationDetails().get(1).getBackupId());
        Assertions.assertNotNull(operationStatus.readEntity(OperationStatusResponse.class).getOperationDetails().get(1).getRestoreId());
    }

    @Test
    void testTerminateOperation() {
        ProcessInstanceImpl testProcessInstance = createTestProcessInstance();
        String trackingId = "trackingId";
        when(testProcessInstance.getState()).thenReturn(TaskState.TERMINATED);
        doNothing().when(processService).terminateProcess(trackingId);
        when(processService.getProcess(trackingId)).thenReturn(testProcessInstance);
        Response operationStatus = blueGreenControllerV1.terminateOperaion(trackingId);
        assertEquals(200, operationStatus.getStatus());
        assertEquals("0 of 2 databases are processed", operationStatus.readEntity(OperationStatusResponse.class).getMessage());
        Assertions.assertEquals(TaskState.TERMINATED, operationStatus.readEntity(OperationStatusResponse.class).getStatus());
        verify(processService).terminateProcess(trackingId);
    }

    @Test
    void testToOrphanDatabasesResponse() {
        List<String> namespaces = List.of("ns-1", "ns-2");
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(new Database());
        databaseRegistry.setName("dbName");
        databaseRegistry.setNamespace(NAMESPACE);
        databaseRegistry.setType("postgresql");
        databaseRegistry.setPhysicalDatabaseId("adapter");
        databaseRegistry.setTimeDbCreation(new Date());
        databaseRegistry.setClassifier(new TreeMap<>(Map.of("namespace", NAMESPACE)));
        when(blueGreenService.getOrphanDatabases(eq(namespaces))).thenReturn(List.of(databaseRegistry));

        Response orphanDatabasesResponseEntity = blueGreenControllerV1.getOrphans(namespaces);

        List<OrphanDatabasesResponse> orphanDatabasesResponse = orphanDatabasesResponseEntity.readEntity(new GenericType<List<OrphanDatabasesResponse>>() {
        });
        Assertions.assertEquals(1, orphanDatabasesResponse.size());
        OrphanDatabasesResponse targetResponse = orphanDatabasesResponse.get(0);
        Assertions.assertEquals("dbName", targetResponse.getDbName());
        Assertions.assertNotNull(targetResponse.getClassifier());
        Assertions.assertEquals(NAMESPACE, targetResponse.getNamespace());
        Assertions.assertEquals("postgresql", targetResponse.getType());
        Assertions.assertNull(targetResponse.getBgVersion());
        Assertions.assertEquals("adapter", targetResponse.getPhysicalDbId());
        Assertions.assertNotNull(targetResponse.getDbCreationTime());
    }

    @Test
    void testListDomains() {
        BgDomain bgDomain1 = new BgDomain();
        bgDomain1.setId(UUID.randomUUID());
        BgNamespace oNamespace = createBgNamespace("origin-namespace1", ACTIVE_STATE, "v1");
        BgNamespace pNamespace = createBgNamespace("peer-namespace1", CANDIDATE_STATE, "v2");
        bgDomain1.setNamespaces(List.of(oNamespace, pNamespace));
        bgDomain1.setControllerNamespace("controller-namespace1");
        bgDomain1.setOriginNamespace("origin-namespace1");
        bgDomain1.setPeerNamespace("peer-namespace1");
        BgDomain bgDomain2 = new BgDomain();
        bgDomain2.setId(UUID.randomUUID());
        BgNamespace oNamespace2 = createBgNamespace("origin-namespace2", ACTIVE_STATE, "v1");
        BgNamespace pNamespace2 = createBgNamespace("peer-namespace2", CANDIDATE_STATE, "v2");
        bgDomain2.setNamespaces(List.of(oNamespace2, pNamespace2));
        bgDomain2.setControllerNamespace("controller-namespace2");
        bgDomain2.setOriginNamespace(null); //
        bgDomain2.setPeerNamespace(null); // To test fallback for old domains without required fields
        when(blueGreenService.getDomains()).thenReturn(List.of(bgDomain1, bgDomain2));

        Response response = blueGreenControllerV1.listBgDomains();
        List<BgDomainForList> bgDomains = response.readEntity(List.class);
        assertEquals(2, bgDomains.size());
        assertEquals("controller-namespace1", bgDomains.get(0).getControllerNamespace());
        assertEquals("origin-namespace1", bgDomains.get(0).getOriginNamespace());
        assertEquals("peer-namespace1", bgDomains.get(0).getPeerNamespace());
        assertEquals("controller-namespace2", bgDomains.get(1).getControllerNamespace());
        assertEquals("origin-namespace2", bgDomains.get(1).getOriginNamespace());
        assertEquals("peer-namespace2", bgDomains.get(1).getPeerNamespace());
    }

    private static BgNamespace createBgNamespace(String namespace, String state) {
        return createBgNamespace(namespace, state, null);
    }

    private static BgNamespace createBgNamespace(String namespace, String state, String version) {
        BgNamespace bgNamespace = new BgNamespace();
        bgNamespace.setNamespace(namespace);
        bgNamespace.setState(state);
        bgNamespace.setVersion(version);
        return bgNamespace;
    }


    @NotNull
    private static BgStateRequest getBgStateRequest(BgStateRequest.BGStateNamespace originNamespace, BgStateRequest.BGStateNamespace peerNamespace) {
        BgStateRequest bgStateRequest = new BgStateRequest();
        BgStateRequest.BGState bgState = new BgStateRequest.BGState();
        bgState.setOriginNamespace(originNamespace);
        bgState.setPeerNamespace(peerNamespace);
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

    @NotNull
    private ProcessInstanceImpl createTestProcessInstance() {
        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        TaskInstanceImpl task1 = mock(TaskInstanceImpl.class);
        when(task1.getState()).thenReturn(TaskState.IN_PROGRESS);
        when(task1.getType()).thenReturn(NewDatabaseTask.class.getName());
        TaskInstanceImpl task2 = mock(TaskInstanceImpl.class);
        when(task2.getState()).thenReturn(TaskState.IN_PROGRESS);
        when(task2.getType()).thenReturn(BackupDatabaseTask.class.getName());
        TaskInstanceImpl task3 = mock(TaskInstanceImpl.class);
        when(task3.getState()).thenReturn(TaskState.NOT_STARTED);
        when(task3.getType()).thenReturn(RestoreDatabaseTask.class.getName());
        TaskInstanceImpl task4 = mock(TaskInstanceImpl.class);
        when(task4.getState()).thenReturn(TaskState.NOT_STARTED);
        when(task4.getType()).thenReturn(DeleteBackupTask.class.getName());

        DatabaseDeclarativeConfig config = createDatabaseDeclarativeConfig("test-ms", "test-ns");
        NewDatabaseProcessObject newDatabaseProcessObject = new NewDatabaseProcessObject(config, "v2");
        DataContext dataContextNew = mock(DataContext.class);
        when(dataContextNew.get("processObject")).thenReturn(newDatabaseProcessObject);
        when(task1.getContext()).thenReturn(dataContextNew);
        DataContext dataContext = mock(DataContext.class);
        CloneDatabaseProcessObject cloneDatabaseProcessObject = new CloneDatabaseProcessObject(config, "v2", new TreeMap<>(), "source-ns");
        when(dataContext.get("processObject")).thenReturn(cloneDatabaseProcessObject);
        when(task2.getContext()).thenReturn(dataContext);
        when(task3.getContext()).thenReturn(dataContext);
        when(task4.getContext()).thenReturn(dataContext);

        TaskInstanceImpl task5 = mock(TaskInstanceImpl.class);
        when(task5.getState()).thenReturn(TaskState.IN_PROGRESS);
        when(task5.getName()).thenReturn(UPDATE_BG_STATE_TASK);

        when(processInstance.getTasks()).thenReturn(List.of(task1, task2, task3, task4, task5));
        return processInstance;
    }

    private DatabaseDeclarativeConfig createDatabaseDeclarativeConfig(String microserviceName, String namespace) {
        DatabaseDeclarativeConfig result = new DatabaseDeclarativeConfig();
        result.setType("postgresql");

        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("scope", "service");
        classifier.put("microserviceName", microserviceName);
        classifier.put("namespace", namespace);
        result.setNamespace(namespace);
        result.setClassifier(classifier);
        return result;
    }

    private static BgStateRequest.BGStateNamespace createBgStateNamespace(String state, String namespace) {
        return createBgStateNamespace(state, namespace, null);
    }

    private BgStateRequest createBgStateRequest() {
        BgStateRequest bgStateRequest = new BgStateRequest();
        var bgState = new BgStateRequest.BGState();
        BgStateRequest.BGStateNamespace bgNamespace = createBgStateNamespace(CANDIDATE_STATE, "test-namespace", "v2");
        bgState.setUpdateTime(new Date());
        bgState.setOriginNamespace(bgNamespace);
        bgState.setPeerNamespace(bgNamespace);
        bgStateRequest.setBGState(bgState);
        return bgStateRequest;
    }

    @NotNull
    private BgStateRequest.BGStateNamespace createRequestNamespace() {
        BgStateRequest.BGStateNamespace bgNamespace = new BgStateRequest.BGStateNamespace();
        bgNamespace.setName("test-namespace");
        bgNamespace.setState(CANDIDATE_STATE);
        bgNamespace.setVersion("v2");
        return bgNamespace;
    }
}