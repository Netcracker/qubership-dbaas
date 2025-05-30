package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.API_VERSION;
import org.qubership.cloud.dbaas.dto.RegisterDatabaseWithUserCreationRequest;
import org.qubership.cloud.dbaas.dto.migration.RegisterDatabaseResponseBuilder;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.RegisterDatabaseRequestV3;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.MigrationService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.NAMESPACE;
import static org.qubership.cloud.dbaas.Constants.ROLE;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;


@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DatabasesMigrationControllerV3.class)
@Slf4j
class DatabasesMigrationControllerV3Test {

    @InjectMock
    MigrationService migrationService;
    @InjectMock
    DBaaService dBaaService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testRegisterDatabases() throws Exception {
        final List<RegisterDatabaseRequestV3> registerDatabaseRequestList = Collections.singletonList(getRegisterDatabaseRequestSample());

        final RegisterDatabaseResponseBuilder registerDatabaseResponseBuilder = registerDatabaseResponseBuilderSample();

        when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        when(migrationService.registerDatabases(any(), eq(API_VERSION.V3), eq(false))).thenReturn(registerDatabaseResponseBuilder);
        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(registerDatabaseRequestList))
                .when().put()
                .then()
                .statusCode(OK.getStatusCode());

        registerDatabaseResponseBuilder.addConflictedDb("test-db", "test-type");

        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(registerDatabaseRequestList))
                .when().put()
                .then()
                .statusCode(CONFLICT.getStatusCode());

        registerDatabaseResponseBuilder.addFailedDb("test-failed-db", "test-type");

        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(registerDatabaseRequestList))
                .when().put()
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    @NotNull
    private static RegisterDatabaseWithUserCreationRequest getRegisterDatabaseWithUserCreationRequest() {
        RegisterDatabaseWithUserCreationRequest request
                = new RegisterDatabaseWithUserCreationRequest();
        request.setClassifier(getClassifier());
        request.setType("postgresql");
        request.setName("test-name");
        request.setPhysicalDatabaseId("123");
        request.setDbHost("test-service.test-namespace");
        return request;
    }

    @NotNull
    private static RegisterDatabaseWithUserCreationRequest getRegisterDatabaseWithUserCreationRequest(SortedMap<String, Object> classifier,
                                                                                                      String dbType,
                                                                                                      String namespace,
                                                                                                      String dbName,
                                                                                                      String physicalDbId) {
        RegisterDatabaseWithUserCreationRequest request
                = new RegisterDatabaseWithUserCreationRequest();
        request.setClassifier(classifier);
        request.setType(dbType);
        request.setName(dbName);
        request.setPhysicalDatabaseId(physicalDbId);
        return request;
    }

    @Test
    void testRegisterDbWithUserCreation_ValidateRequest() throws Exception {
        when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        RegisterDatabaseWithUserCreationRequest validRegisteredRecord = getRegisterDatabaseWithUserCreationRequest();
        RegisterDatabaseWithUserCreationRequest notValidRegisteredRecord = getRegisterDatabaseWithUserCreationRequest(
                getClassifier(),
                null,
                "ns-1",
                "db-name-1",
                "phys-db-1"
        );
        List<RegisterDatabaseWithUserCreationRequest> requests = Arrays.asList(validRegisteredRecord, notValidRegisteredRecord);

        // dbType
        doRegistrationCallAndCheck(requests, "dbType", "/1/type");

        notValidRegisteredRecord.setType("postgresql");
        notValidRegisteredRecord.getClassifier().remove(NAMESPACE);

        doRegistrationCallAndCheck(requests, "namespace in classifier", "/1/classifier/namespace");

        // dbName
        notValidRegisteredRecord.setClassifier(getClassifier());
        notValidRegisteredRecord.setName("");

        doRegistrationCallAndCheck(requests, "database name", "/1/name");

        // physical database name
        notValidRegisteredRecord.setPhysicalDatabaseId(null);
        notValidRegisteredRecord.setName("db-name-1");

        doRegistrationCallAndCheck(requests, "physical database id or dbHost", "/1/physicalDatabaseId");

        // dbHost not in format <service>.<namespace>
        notValidRegisteredRecord.setPhysicalDatabaseId("phys-db-1");
        notValidRegisteredRecord.setDbHost("not-very-correct-db-host");

        String msg = "register request contains dbHost field, but it has wrong format: not-very-correct-db-host." +
                "Must be in format: <service-name>.<namesapce>, e.g.: pg-patroni.postgresql-core";
        doRegistrationCallAndCheckMessage(requests, msg, "/1/dbHost");

        // classifier
        notValidRegisteredRecord.setDbHost("postgresql.postgresql");
        notValidRegisteredRecord.setClassifier(null);

        doRegistrationCallAndCheck(requests, "classifier", "/1/classifier");

        TreeMap<String, Object> classifier = getClassifier();
        classifier.remove("microserviceName");
        notValidRegisteredRecord.setClassifier(classifier);

        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(requests))
                .when().put("/with-user-creation")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("Invalid classifier. It does not match v3 format. Classifier: {namespace=TEST_NAMESPACE, scope=service}"));
    }

    @Test
    void testRegisterDbWithUserCreation_SuccessRequest() throws Exception {
        RegisterDatabaseWithUserCreationRequest validRegisteredRecord = getRegisterDatabaseWithUserCreationRequest();
        List<RegisterDatabaseWithUserCreationRequest> requests = List.of(validRegisteredRecord);
        when(dBaaService.isValidClassifierV3(any())).thenReturn(true);
        when(migrationService.registerDatabases(any(), eq(API_VERSION.V3), eq(true)))
                .thenReturn(new RegisterDatabaseResponseBuilder());

        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(requests))
                .when().put("/with-user-creation")
                .then()
                .statusCode(OK.getStatusCode());
    }

    private void doRegistrationCallAndCheck(List<RegisterDatabaseWithUserCreationRequest> requests, String value, String pointer) throws Exception {
        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(requests))
                .when().put("/with-user-creation")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is("registered database must contain " + value))
                .body("source.pointer", is(pointer));
    }

    private void doRegistrationCallAndCheckMessage(List<RegisterDatabaseWithUserCreationRequest> requests, String message, String pointer) throws Exception {
        given().auth().preemptive().basic("migration-client", "migration-client")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(requests))
                .when().put("/with-user-creation")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("message", is(message))
                .body("source.pointer", is(pointer));
    }

    private RegisterDatabaseRequestV3 getRegisterDatabaseRequestSample() {
        final RegisterDatabaseRequestV3 registerDatabaseRequest = new RegisterDatabaseRequestV3();
        registerDatabaseRequest.setName("test-name");
        registerDatabaseRequest.setType("test-type");
        registerDatabaseRequest.setNamespace("test-namespace");
        final DbResource dbResource = new DbResource("test-db", "test-name");
        registerDatabaseRequest.setResources(Collections.singletonList(dbResource));
        registerDatabaseRequest.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put(ROLE, Role.ADMIN);
        }}));
        TreeMap<String, Object> classifier = getClassifier();
        registerDatabaseRequest.setClassifier(classifier);
        return registerDatabaseRequest;
    }

    private static TreeMap<String, Object> getClassifier() {
        TreeMap<String, Object> classifier = new TreeMap<>();
        classifier.put("namespace", "TEST_NAMESPACE");
        classifier.put("scope", "service");
        classifier.put("microserviceName", "test_name");
        return classifier;
    }

    private RegisterDatabaseResponseBuilder registerDatabaseResponseBuilderSample() {
        return new RegisterDatabaseResponseBuilder();
    }
}
