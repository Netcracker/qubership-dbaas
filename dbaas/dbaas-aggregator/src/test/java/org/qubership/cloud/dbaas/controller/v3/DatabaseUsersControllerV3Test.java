package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersResponse;
import org.qubership.cloud.dbaas.dto.userrestore.SuccessfullRestore;
import org.qubership.cloud.dbaas.dto.userrestore.UnsuccessfulRestore;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserRequest;
import org.qubership.cloud.dbaas.dto.v3.GetOrCreateUserResponse;
import org.qubership.cloud.dbaas.dto.userrestore.RestoreUsersRequest;
import org.qubership.cloud.dbaas.dto.v3.UserOperationRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.PasswordEncryption;
import org.qubership.cloud.dbaas.service.ProcessConnectionPropertiesService;
import org.qubership.cloud.dbaas.service.UserService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DatabaseUsersControllerV3.class)
@Slf4j
public class DatabaseUsersControllerV3Test {

    private final String POSTGRESQL = "postgresql";

    @InjectMock
    DBaaService dBaaService;
    @InjectMock
    @MockitoConfig(convertScopes = true)
    PasswordEncryption encryption;
    @InjectMock
    UserService userService;
    @InjectMock
    ProcessConnectionPropertiesService processConnectionPropertiesService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createUserTest() throws Exception {
        Database database = createDatabase();
        GetOrCreateUserRequest request = createGetOrCreateUserRequest();
        Mockito.when(dBaaService.findDatabaseByClassifierAndType(
                eq(request.getClassifier()),
                eq(request.getType()),
                eq(true))).thenReturn(database.getDatabaseRegistry().get(0));
        Mockito.when(userService.findUserByLogicalUserIdAndDatabaseId(eq(request.getLogicalUserId()), eq(database))).thenReturn(Optional.empty());
        Mockito.when(userService.createUser(eq(request), eq(database))).thenReturn(new GetOrCreateUserResponse("userId", new HashMap<>()));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put()
                .then()
                .statusCode(CREATED.getStatusCode())
                .body("userId", is("userId"));
    }

    @NotNull
    private static Database createDatabase() {
        Database database = new Database();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        databaseRegistry.setDatabase(database);
        return database;
    }

    @Test
    void getUserTest() throws Exception {
        Database database = createDatabase();
        GetOrCreateUserRequest request = createGetOrCreateUserRequest();
        Mockito.when(dBaaService.findDatabaseByClassifierAndType(
                eq(request.getClassifier()),
                eq(request.getType()),
                eq(true))).thenReturn(database.getDatabaseRegistry().get(0));
        String userId = UUID.randomUUID().toString();
        DatabaseUser user = createDatabaseUser(userId, database);
        Mockito.when(dBaaService.getConnectionPropertiesService()).thenReturn(processConnectionPropertiesService);
        Mockito.when(userService.findUserByLogicalUserIdAndDatabaseId(eq(request.getLogicalUserId()), eq(database))).thenReturn(Optional.of(user));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode())
                .body("userId", is(userId));
    }

    @Test
    void createUserWhenDatabaseNotFound() throws Exception {
        GetOrCreateUserRequest request = createGetOrCreateUserRequest();
        Mockito.when(dBaaService.findDatabaseByClassifierAndType(
                eq(request.getClassifier()),
                eq(request.getType()),
                eq(true))).thenReturn(null);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void deleteUserTest() throws Exception {
        UserOperationRequest request = createDeleteUserRequest();
        DatabaseUser user = new DatabaseUser();
        user.setUserId(UUID.randomUUID());
        Mockito.when(userService.findUser(eq(request))).thenReturn(Optional.of(user));
        Mockito.when(userService.deleteUser(eq(user))).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().delete()
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void deleteUserWhenUserNotFoundTest() throws Exception {
        UserOperationRequest request = createDeleteUserRequest();
        Mockito.when(userService.findUser(eq(request))).thenReturn(Optional.empty());
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().delete()
                .then()
                .statusCode(NO_CONTENT.getStatusCode());
    }

    @Test
    void deleteUserWhenExceptionTest() throws Exception {
        UserOperationRequest request = createDeleteUserRequest();
        DatabaseUser user = new DatabaseUser();
        user.setUserId(UUID.randomUUID());
        Mockito.when(userService.findUser(eq(request))).thenReturn(Optional.of(user));
        Mockito.when(userService.deleteUser(eq(user))).thenReturn(false);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put()
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void rotateUserPasswordTest() throws Exception {
        UserOperationRequest request = createDeleteUserRequest();
        DatabaseUser user = new DatabaseUser();
        user.setConnectionProperties(new HashMap<>());
        Mockito.when(userService.findUser(eq(request))).thenReturn(Optional.of(user));
        Mockito.when(userService.rotatePassword(eq(user))).thenReturn(user);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().post("/rotate-password")
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void restoreUsersTest() throws JsonProcessingException {
        RestoreUsersRequest restoreUsersRequest = createSampleRestoreUsersRequest();
        Mockito.when(userService.restoreUsers(eq(restoreUsersRequest)))
                .thenReturn(new RestoreUsersResponse( new ArrayList<>(), List.of(new SuccessfullRestore())));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(restoreUsersRequest))
                .when().post("/restore")
                .then()
                .statusCode(OK.getStatusCode());
    }

    @Test
    void restoreUsersTestAdapterNoySupportUsersError() throws Exception {
        RestoreUsersRequest restoreUsersRequest = createSampleRestoreUsersRequest();
        Mockito.when(userService.restoreUsers(eq(restoreUsersRequest)))
                .thenReturn(new RestoreUsersResponse( List.of(new UnsuccessfulRestore()), List.of(new SuccessfullRestore())));
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(restoreUsersRequest))
                .when().post("/restore")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    private UserOperationRequest createDeleteUserRequest() {
        UserOperationRequest req = new UserOperationRequest();
        req.setUserId("test-user-id");
        return req;
    }

    private GetOrCreateUserRequest createGetOrCreateUserRequest() {
        return new GetOrCreateUserRequest(simpleClassifier(), "some-id", POSTGRESQL, "test-id", "prefix", "rw");
    }

    private SortedMap<String, Object> simpleClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test-microservice");
        classifier.put("scope", "service");
        classifier.put("namespace", "test-namespace");
        return classifier;
    }

    private DatabaseUser createDatabaseUser(String userId, Database database) {
        return new DatabaseUser(UUID.fromString(userId),
                database, "test-id", "rw",
                null, DatabaseUser.CreationMethod.ON_REQUEST,
                new HashMap<>(), null, DatabaseUser.Status.CREATED);
    }

    private RestoreUsersRequest createSampleRestoreUsersRequest(){
        SortedMap<String, Object> classifier = simpleClassifier();
        return new RestoreUsersRequest(classifier, "postgresql", "admin");
    }
}
