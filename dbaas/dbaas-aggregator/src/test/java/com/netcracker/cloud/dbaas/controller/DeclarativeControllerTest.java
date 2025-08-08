package com.netcracker.cloud.dbaas.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.RolesRegistrationRequest;
import com.netcracker.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.bluegreen.CloneDatabaseProcessObject;
import com.netcracker.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import com.netcracker.cloud.dbaas.dto.declarative.DeclarativeDatabaseCreationRequest;
import com.netcracker.cloud.dbaas.dto.role.ServiceRole;
import com.netcracker.cloud.dbaas.entity.pg.BgTrack;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseDeclarativeConfig;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.service.*;
import com.netcracker.core.scheduler.po.DataContext;
import com.netcracker.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import com.netcracker.core.scheduler.po.model.pojo.TaskInstanceImpl;
import com.netcracker.core.scheduler.po.task.TaskState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static com.netcracker.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static com.netcracker.cloud.dbaas.service.processengine.Const.RESTORE_TASK;
import static com.netcracker.cloud.dbaas.service.processengine.Const.UPDATE_BG_STATE_TASK;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.ACCEPTED;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DeclarativeController.class)
class DeclarativeControllerTest {

    private static final String TEST_NAMESPACE = "test-namespace";

    @InjectMock
    DatabaseRolesService databaseRolesDbaasRepository;
    @InjectMock
    DeclarativeDbaasCreationService dbaasCreationService;
    @InjectMock
    BlueGreenService blueGreenService;
    @InjectMock
    ProcessService processService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        BgTrack track = new BgTrack();
        track.setId(UUID.randomUUID().toString());
        ProcessInstanceImpl processInstance = Mockito.mock(ProcessInstanceImpl.class);
        when(processInstance.getId()).thenReturn(UUID.randomUUID().toString());
        ArrayList<AbstractDatabaseProcessObject> list = new ArrayList<>();
        when(dbaasCreationService.saveDeclarativeDatabase(any(), any(), any())).thenReturn(list);
        when(dbaasCreationService.startProcessInstance(any(), any())).thenReturn(processInstance);
        when(processService.getProcess(processInstance.getId())).thenReturn(processInstance);
    }

    @Test
    void testDeclarativeControllerRolesRequest() throws JsonProcessingException {
        RolesRegistrationRequest rolesRegistrationRequest = getRolesRegistrationRequest();
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(rolesRegistrationRequest))
                .when().post("/namespaces/{" + NAMESPACE_PARAMETER + "}" + "/service/{serviceName}", "test-service")
                .then()
                .statusCode(OK.getStatusCode());
        Mockito.verify(databaseRolesDbaasRepository).saveRequestedRoles(any(), any(), any());
    }

    @Test
    void testDeclarativeControllerDbCreateRequestAndRole() throws JsonProcessingException {
        RolesRegistrationRequest rolesRegistrationRequest = getRolesRegistrationRequest();
        DeclarativeDatabaseCreationRequest dbCreateRequest = getDeclarativeDatabaseCreationRequest();
        List<Object> listRequest = new ArrayList<>();
        listRequest.add(dbCreateRequest);
        listRequest.add(rolesRegistrationRequest);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(listRequest))
                .when().post("/namespaces/{" + NAMESPACE_PARAMETER + "}" + "/service/{serviceName}", "test-service")
                .then()
                .statusCode(ACCEPTED.getStatusCode());
        Mockito.verify(databaseRolesDbaasRepository).saveRequestedRoles(any(), any(), any());
        Mockito.verify(dbaasCreationService).saveDeclarativeDatabase(any(), any(), any());
    }

    @Test
    void testGetOperationStatus() {
        ProcessInstanceImpl testProcessInstance = createTestProcessInstance(TaskState.COMPLETED, TaskState.COMPLETED);
        when(testProcessInstance.getState()).thenReturn(TaskState.COMPLETED);
        String trackingId = "trackingId";
        when(processService.getProcess(trackingId)).thenReturn(testProcessInstance);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/status/{" + trackingId + "}", trackingId)
                .then()
                .statusCode(OK.getStatusCode())
                .body("state", is(BlueGreenStatusUtil.taskStatusToString(TaskState.COMPLETED)));
    }

    @Test
    void testGetOperationStatusInProgress() {
        ProcessInstanceImpl testProcessInstance = createTestProcessInstance(TaskState.COMPLETED, TaskState.IN_PROGRESS);
        when(testProcessInstance.getState()).thenReturn(TaskState.IN_PROGRESS);
        String trackingId = "trackingId";
        when(processService.getProcess(trackingId)).thenReturn(testProcessInstance);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/status/{" + trackingId + "}", trackingId)
                .then()
                .statusCode(ACCEPTED.getStatusCode())
                .body("state", is(BlueGreenStatusUtil.taskStatusToString(TaskState.IN_PROGRESS)));
    }


    @Test
    void testDeclarativeControllerDbCreateRequest() throws JsonProcessingException {
        DeclarativeDatabaseCreationRequest dbCreateRequest = getDeclarativeDatabaseCreationRequest();
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(dbCreateRequest))
                .when().post("/namespaces/{" + NAMESPACE_PARAMETER + "}" + "/service/{serviceName}", "test-service")
                .then()
                .statusCode(ACCEPTED.getStatusCode());
        Mockito.verify(dbaasCreationService).saveDeclarativeDatabase(any(), any(), any());
    }

    @Test
    void testDeclarativeControllerUnknownConfigKing() throws JsonProcessingException {
        RolesRegistrationRequest rolesRegistrationRequest = getRolesRegistrationRequest();
        rolesRegistrationRequest.setKind("some-custom-kind");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(rolesRegistrationRequest))
                .when().post("/namespaces/{" + NAMESPACE_PARAMETER + "}" + "/service/{serviceName}", "test-service")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode());
        Mockito.verifyNoInteractions(databaseRolesDbaasRepository);
        Mockito.verifyNoInteractions(dbaasCreationService);
    }

    @NotNull
    private DeclarativeDatabaseCreationRequest getDeclarativeDatabaseCreationRequest() {
        DeclarativeDatabaseCreationRequest dbCreateRequest = new DeclarativeDatabaseCreationRequest();
        dbCreateRequest.setApiVersion("v3");
        dbCreateRequest.setKind("DatabaseDeclaration");
        List<DatabaseDeclaration> list = new ArrayList<>();
        DatabaseDeclaration config = new DatabaseDeclaration();
        config.setType("postgresql");
        DatabaseDeclaration.ClassifierConfig classifierConfig = new DatabaseDeclaration.ClassifierConfig();
        classifierConfig.setClassifier(new TreeMap<>(Collections.singletonMap("key", "value")));
        config.setClassifierConfig(classifierConfig);
        list.add(config);
        dbCreateRequest.setDeclarations(list);
        return dbCreateRequest;
    }

    @NotNull
    private ProcessInstanceImpl createTestProcessInstance(TaskState ts1, TaskState ts2) {
        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        TaskInstanceImpl task1 = mock(TaskInstanceImpl.class);
        when(task1.getState()).thenReturn(ts1);
        when(task1.getName()).thenReturn(RESTORE_TASK + ":" + UUID.randomUUID().toString());

        DataContext dataContext = mock(DataContext.class);
        DatabaseDeclarativeConfig config = createDatabaseDeclarativeConfig("test-ms", "test-ns");
        CloneDatabaseProcessObject cloneDatabaseProcessObject = new CloneDatabaseProcessObject(config, "v2", new TreeMap<>(), "source-ns");
        when(dataContext.get("processObject")).thenReturn(cloneDatabaseProcessObject);
        when(task1.getContext()).thenReturn(dataContext);

        TaskInstanceImpl task2 = mock(TaskInstanceImpl.class);
        when(task2.getName()).thenReturn(UPDATE_BG_STATE_TASK);

        when(processInstance.getTasks()).thenReturn(List.of(task1, task2));
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

    @NotNull
    private RolesRegistrationRequest getRolesRegistrationRequest() {
        RolesRegistrationRequest rolesRegistrationRequest = new RolesRegistrationRequest("qs.core.dbaas/v3");
        rolesRegistrationRequest.setKind("dbPolicy");
        rolesRegistrationRequest.setServices(List.of(new ServiceRole()));
        return rolesRegistrationRequest;
    }
}
