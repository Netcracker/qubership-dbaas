package org.qubership.cloud.dbaas.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.bluegreen.AbstractDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.bluegreen.NewDatabaseProcessObject;
import org.qubership.cloud.dbaas.dto.conigs.DeclarativeConfig;
import org.qubership.cloud.dbaas.dto.conigs.RolesRegistration;
import org.qubership.cloud.dbaas.dto.declarative.DatabaseDeclaration;
import org.qubership.cloud.dbaas.dto.declarative.DeclarativePayload;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DatabaseRolesService;
import org.qubership.cloud.dbaas.service.DeclarativeDbaasCreationService;
import org.qubership.cloud.dbaas.service.ProcessService;
import org.qubership.core.scheduler.po.DataContext;
import org.qubership.core.scheduler.po.model.pojo.ProcessInstanceImpl;
import org.qubership.core.scheduler.po.model.pojo.TaskInstanceImpl;
import org.qubership.core.scheduler.po.task.TaskState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.cloud.dbaas.Constants.DATABASE_DECLARATION_CONFIG_TYPE;
import static org.qubership.cloud.dbaas.Constants.DB_POLICY_CONFIG_TYPE;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(ConfigControllerV1.class)
class ConfigControllerV1Test {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMock
    DatabaseRolesService databaseRolesService;
    @InjectMock
    DeclarativeDbaasCreationService dbaasCreationService;
    @InjectMock
    ProcessService processService;

    @Test
    void applyConfigs_200_SyncOperationDone() throws JsonProcessingException {
        String namespace = "namespace";
        String microservice = "microservice";

        DeclarativePayload payload = new DeclarativePayload();
        DeclarativePayload.Metadata metadata = new DeclarativePayload.Metadata();
        metadata.setNamespace(namespace);
        metadata.setMicroserviceName(microservice);
        payload.setKind("DBaaS");
        payload.setSubKind(DB_POLICY_CONFIG_TYPE);
        payload.setMetadata(metadata);
        payload.setSpec(new RolesRegistration());

        doNothing().when(databaseRolesService).saveRequestedRoles(eq(namespace), eq(microservice), any());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void applyConfigs_202_AsyncOperationStarted() throws JsonProcessingException {
        String namespace = "namespace";
        String microservice = "microservice";

        DeclarativePayload payload = new DeclarativePayload();
        DeclarativePayload.Metadata metadata = new DeclarativePayload.Metadata();
        metadata.setNamespace(namespace);
        metadata.setMicroserviceName(microservice);
        payload.setKind("DBaaS");
        payload.setSubKind(DATABASE_DECLARATION_CONFIG_TYPE);
        payload.setMetadata(metadata);
        payload.setSpec(new DatabaseDeclaration());

        ArrayList<AbstractDatabaseProcessObject> processObjects = new ArrayList<>();
        AbstractDatabaseProcessObject processObject = new NewDatabaseProcessObject(null, "");
        processObjects.add(processObject);
        doReturn(processObjects).when(dbaasCreationService).saveDeclarativeDatabase(eq(namespace), eq(microservice), any());

        String processId = "process_id";
        TaskInstanceImpl taskInstance = new TaskInstanceImpl("task_id", "name", "type", processId);
        taskInstance.setState(TaskState.IN_PROGRESS);

        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        doReturn(processId).when(processInstance).getId();
        doReturn(TaskState.IN_PROGRESS).when(processInstance).getState();
        doReturn(List.of(taskInstance)).when(processInstance).getTasks();
        doReturn(processInstance).when(dbaasCreationService).startProcessInstance(eq(namespace), eq(processObjects));

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(ACCEPTED.getStatusCode());
    }

    @Test
    void applyConfigs_400_ValidationError() throws JsonProcessingException {
        String namespace = "namespace";
        String microservice = "microservice";

        DeclarativePayload payload = new DeclarativePayload();
        DeclarativePayload.Metadata metadata = new DeclarativePayload.Metadata();
        metadata.setNamespace(namespace);
        metadata.setMicroserviceName(microservice);
        payload.setKind("DBaaS");
        payload.setSubKind(DB_POLICY_CONFIG_TYPE);
        payload.setMetadata(metadata);
        payload.setSpec(new RolesRegistration());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(OK.getStatusCode());

        metadata.setNamespace("");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("Empty namespace"));

        metadata.setNamespace(namespace);
        metadata.setMicroserviceName("");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("Empty microservice name"));

        metadata.setMicroserviceName(microservice);
        payload.setSubKind("");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("Empty SubKind"));

        payload.setSubKind("wrongSubkind");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("Unknown SubKind value"));

        payload.setSubKind(DATABASE_DECLARATION_CONFIG_TYPE);
        metadata.setMicroserviceName(microservice);
        payload.setSpec(null);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("Empty spec"));

        payload.setSpec(new DeclarativeConfig() {
            public final String declarations = "123";
        });
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", containsString("Unrecognized field \"declarations\""));
    }

    @Test
    void applyConfigs_500_InternalError() throws JsonProcessingException {
        String namespace = "namespace";
        String microservice = "microservice";

        DeclarativePayload payload = new DeclarativePayload();
        DeclarativePayload.Metadata metadata = new DeclarativePayload.Metadata();
        metadata.setNamespace(namespace);
        metadata.setMicroserviceName(microservice);
        payload.setKind("DBaaS");
        payload.setSubKind(DB_POLICY_CONFIG_TYPE);
        payload.setMetadata(metadata);
        payload.setSpec(new RolesRegistration());

        doThrow(new RuntimeException()).when(databaseRolesService).saveRequestedRoles(eq(namespace), eq(microservice), any());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .when().post("/apply")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @Test
    void getOperationStatus_200_InProgress() {
        String processId = "process_id";
        TaskInstanceImpl taskInstance = mock(TaskInstanceImpl.class);
        doReturn(TaskState.IN_PROGRESS).when(taskInstance).getState();
        DataContext dataContext = new DataContext("task_id_context");
        dataContext.put("waitingForResources", "true");
        dataContext.put("stateDescription", "waiting_description");
        doReturn(dataContext).when(taskInstance).getContext();

        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        doReturn(TaskState.IN_PROGRESS).when(processInstance).getState();
        doReturn(List.of(taskInstance)).when(processInstance).getTasks();

        doReturn(processInstance).when(processService).getProcess(eq(processId));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/operation/{trackingId}/status", processId)
                .then()
                .statusCode(OK.getStatusCode())
                .body("status", is("IN_PROGRESS"))
                .body("conditions[1].message", containsString("waiting_description"));
    }

    @Test
    void getOperationStatus_404_NotExistingTrackingId() {
        doReturn(null).when(processService).getProcess(anyString());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get("/operation/{trackingId}/status", "12345")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void terminateOperation_204_Completed() {
        ProcessInstanceImpl processInstance = mock(ProcessInstanceImpl.class);
        doReturn(processInstance).when(processService).getProcess(anyString());
        doNothing().when(processService).terminateProcess(anyString());

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().post("/operation/{trackingId}/terminate", "12345")
                .then()
                .statusCode(NO_CONTENT.getStatusCode());
        Mockito.verify(processService, Mockito.times(1)).terminateProcess(anyString());
    }
}