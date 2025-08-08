package com.netcracker.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.qubership.cloud.dbaas.dto.PasswordChangeResponse;
import org.qubership.cloud.dbaas.dto.RecreateDatabaseRequest;
import org.qubership.cloud.dbaas.dto.LinkDatabasesRequest;
import org.qubership.cloud.dbaas.dto.UpdateConnectionPropertiesRequest;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.exceptions.ErrorCodes;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import org.qubership.cloud.dbaas.service.BlueGreenService;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.DatabaseRolesService;
import org.qubership.cloud.dbaas.service.OperationService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Method;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.qubership.cloud.core.error.rest.tmf.TmfErrorResponse.TYPE_V1_0;
import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4007;
import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_4044;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DatabaseOperationControllerV3.class)
class DatabaseOperationControllerV3Test {

    private static final String TEST_NAMESPACE = "test-namespace";
    private static final String PG_TYPE = "postgresql";
    private static final String RECREATE_DB_API_V3 = "/databases/recreate";
    private static final String LINK_DB_API_V3 = "/databases/link";
    private static final String UPD_CP_API_V3 = "/databases/update-connection/{type}";

    @InjectMock
    DBaaService dBaaService;
    @InjectMock
    PhysicalDatabaseDbaasRepository physicalDatabaseDbaasRepository;
    @InjectMock
    DatabaseRolesService databaseRolesService;
    @InjectMock
    BlueGreenService blueGreenService;
    @InjectMock
    DatabaseRegistryDbaasRepository databaseDbaasRepository;
    @InjectMock
    OperationService operationService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void changePasswordTest() throws Exception {
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(createPasswordChangeRequest(null, null)))
                .when().post("/password-changes")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("code", is(CORE_DBAAS_4007.getCode()))
                .body("reason", is(CORE_DBAAS_4007.getTitle()))
                .body("message", is(CORE_DBAAS_4007.getDetail("The request body is empty or database type is not specified")))
                .body("status", is(String.valueOf(BAD_REQUEST.getStatusCode())))
                .body("@type", is(TYPE_V1_0));
        verify(dBaaService, times(0)).changeUserPassword(any(), any(), any());

        PasswordChangeRequestV3 passwordChangeRequest = createPasswordChangeRequest(ImmutableMap.of("microserviceName", "test"), "mongodb");
        PasswordChangeResponse response = new PasswordChangeResponse();
        when(dBaaService.changeUserPassword(eq(passwordChangeRequest), eq(TEST_NAMESPACE), any())).thenReturn(response);
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(passwordChangeRequest))
                .when().post("/password-changes")
                .then()
                .statusCode(OK.getStatusCode());
        verify(dBaaService).changeUserPassword(eq(passwordChangeRequest), eq(TEST_NAMESPACE), any());
    }

    @Test
    void updateClassifierControllerSuccessStatus() throws Exception {
        SortedMap<String, Object> primaryClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("scope", "service");
            put("microserviceName", "serviceOne");
        }};
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        String adapterId = "someId";
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setPhysicalDatabaseIdentifier("123");
        when(physicalDatabaseDbaasRepository.findByAdapterId(adapterId)).thenReturn(physicalDatabase);
        when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        UpdateClassifierRequestV3 request = createUpdateClassifierRequest(primaryClassifier, targetClassifier);
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(primaryClassifier);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setClassifier(primaryClassifier);
        databaseRegistry.setDatabase(database);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        when(dBaaService.findDatabaseByClassifierAndType(primaryClassifier, mongodb, false)).thenReturn(database.getDatabaseRegistry().get(0));
        Database returnedDatabase = new Database();
        DatabaseRegistry databaseRegistry2 = new DatabaseRegistry();
        databaseRegistry2.setDatabase(returnedDatabase);
        databaseRegistry2.setClassifier(targetClassifier);
        ArrayList<DatabaseRegistry> databaseRegistries2 = new ArrayList<DatabaseRegistry>();
        databaseRegistries2.add(databaseRegistry2);
        returnedDatabase.setDatabaseRegistry(databaseRegistries2);
        returnedDatabase.setAdapterId(adapterId);

        when(dBaaService.updateClassifier(eq(primaryClassifier), eq(targetClassifier), any(), eq(false))).thenReturn(returnedDatabase.getDatabaseRegistry().get(0));
        performRequest(getUpdateClassifierUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 200);
    }

    @Test
    void updateClassifierControllerFailStatus() throws Exception {
        SortedMap<String, Object> primaryClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("scope", "service");
            put("microserviceName", "serviceOne");
        }};
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        UpdateClassifierRequestV3 request = createUpdateClassifierRequest(primaryClassifier, targetClassifier);
        String mongodb = "mongodb";
        performRequest(getUpdateClassifierUrl(mongodb), TEST_NAMESPACE, Method.PUT, new UpdateClassifierRequestV3(), 400);
        performRequest(getUpdateClassifierUrl(mongodb), TEST_NAMESPACE + "1", Method.PUT, request, 400);
    }

    @Test
    void updateClassifierControllerEmptyToClassifier() throws Exception {
        SortedMap<String, Object> primaryClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
        }};
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
        }};
        UpdateClassifierRequestV3 request = createUpdateClassifierRequest(primaryClassifier, targetClassifier);
        String mongodb = "mongodb";
        performRequest(getUpdateClassifierUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 400);
    }

    @Test
    void updateClassifierInvalidToClassifier() throws Exception {
        SortedMap<String, Object> primaryClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("scope", "service");
            put("microserviceName", "serviceOne");
        }};
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();

        UpdateClassifierRequestV3 request = createUpdateClassifierRequest(primaryClassifier, targetClassifier);
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(primaryClassifier);
        when(dBaaService.findDatabaseByClassifierAndType(primaryClassifier, mongodb, false)).thenReturn(null);
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put(getUpdateClassifierUrl(mongodb))
                .then()
                .statusCode(NOT_FOUND.getStatusCode());
    }

    @Test
    void updateClassifierInvalidFromClassifier() throws Exception {
        SortedMap<String, Object> primaryClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("scope", "service");
            put("microserviceName", "serviceOne");
        }};
        SortedMap<String, Object> targetClassifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();

        UpdateClassifierRequestV3 request = createUpdateClassifierRequest(primaryClassifier, targetClassifier);
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(primaryClassifier);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        when(dBaaService.findDatabaseByClassifierAndType(primaryClassifier, mongodb, false)).thenReturn(database.getDatabaseRegistry().get(0));
        when(dBaaService.findDatabaseByClassifierAndType(targetClassifier, mongodb, false)).thenReturn(database.getDatabaseRegistry().get(0));
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put(getUpdateClassifierUrl(mongodb))
                .then()
                .statusCode(CONFLICT.getStatusCode());
    }

    @Test
    void updateConnectionPropertiesSuccessStatus() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = createTestConnectionProperties(Role.ADMIN.toString());
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, Role.ADMIN.toString());
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(classifier);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        doReturn(databaseRegistry).when(dBaaService).findDatabaseByClassifierAndType(classifier, mongodb, true);
        doReturn(databaseRegistry).when(dBaaService).updateDatabaseConnectionProperties(request, mongodb);
        doReturn(new DatabaseResponseV3SingleCP()).when(dBaaService).processConnectionPropertiesV3(eq(database.getDatabaseRegistry().get(0)), eq(Role.ADMIN.toString()));
        performRequest(getUpdateConnectionPropertiesUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 200);
    }

    @Test
    void updateConnectionPropertiesSuccessStatusWithInCorrectEncryptedPassword() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = createTestConnectionPropertiesWithInCorrectEncryptedPassword(Role.ADMIN.toString());
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, Role.ADMIN.toString());
        String mongodb = "mongodb";
        RegisterDatabaseRequestV3 requestV3 = new RegisterDatabaseRequestV3();
        requestV3.setClassifier(classifier);
        Database database = new Database(requestV3);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        doReturn(databaseRegistry).when(dBaaService).findDatabaseByClassifierAndType(classifier, mongodb, true);
        doReturn(databaseRegistry).when(dBaaService).updateDatabaseConnectionProperties(request, mongodb);
        doReturn(new DatabaseResponseV3SingleCP()).when(dBaaService).processConnectionPropertiesV3(database.getDatabaseRegistry().get(0), Role.ADMIN.toString());
        performRequest(getUpdateConnectionPropertiesUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 400);
    }

    @Test
    void updateConnectionPropertiesSuccessStatusWithCorrectEncryptedPassword() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = createTestConnectionPropertiesWithCorrectEncryptedPassword(Role.ADMIN.toString());
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, Role.ADMIN.toString());
        String mongodb = "mongodb";
        RegisterDatabaseRequestV3 requestV3 = new RegisterDatabaseRequestV3();
        requestV3.setClassifier(classifier);
        Database database = new Database(requestV3);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistry.setClassifier(classifier);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        doReturn(databaseRegistry).when(dBaaService).findDatabaseByClassifierAndType(classifier, mongodb, true);
        doReturn(databaseRegistry).when(dBaaService).updateDatabaseConnectionProperties(request, mongodb);
        doReturn(new DatabaseResponseV3SingleCP()).when(dBaaService).processConnectionPropertiesV3(database.getDatabaseRegistry().get(0), Role.ADMIN.toString());
        performRequest(getUpdateConnectionPropertiesUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 200);
    }

    @Test
    void updateConnectionPropertiesEmptyRequest() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = new HashMap<>();
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, Role.ADMIN.toString());
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(classifier);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        String reason = "Invalid update connection properties request";
        String message = "Invalid connection properties request: Database classifier or new connection properties must not be empty.";
        performUpdateConnPropertiesRequest(request, mongodb, reason, message);
    }

    @Test
    void updateConnectionPropertiesNoRoleInConnectionProperties() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = new HashMap<String, Object>() {{
            put("url", "http://test-url");
            put("username", "test");
        }};
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, Role.ADMIN.toString());
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(classifier);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        String reason = "Invalid update connection properties request";
        String message = "Invalid connection properties request: New connection properties must contain key 'role'.";
        performUpdateConnPropertiesRequest(request, mongodb, reason, message);
    }

    @Test
    void updateConnectionPropertiesDbNotFound() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = createTestConnectionProperties(Role.ADMIN.toString());
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, Role.ADMIN.toString());
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(classifier);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        performRequest(getUpdateConnectionPropertiesUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 404);
    }

    @Test
    void updateConnectionPropertiesConnectionForRoleNotFound() throws Exception {
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", TEST_NAMESPACE);
            put("microserviceName", "serviceOne");
            put("scope", "tenant");
            put("tenantId", "serviceOne");
        }};
        Map<String, Object> newConnProperties = createTestConnectionProperties("null_role");
        UpdateConnectionPropertiesRequest request = createUpdateConnectionPropertiesRequest(classifier, newConnProperties, "null_role");
        String mongodb = "mongodb";
        Database database = new Database();
        database.setClassifier(classifier);
        database.setConnectionProperties(Collections.singletonList(new HashMap<String, Object>() {{
            put("role", Role.ADMIN.toString());
        }}));
        performRequest(getUpdateConnectionPropertiesUrl(mongodb), TEST_NAMESPACE, Method.PUT, request, 404);
    }

    @Test
    void recreateDbPhysicalDbNotRegistered() throws Exception {
        String physicalDbId = "123";
        Mockito.when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDbId)).thenReturn(null);
        Mockito.when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(createTestRecreateDatabaseRequest(PG_TYPE, testClassifier(), physicalDbId)))
                .when().post(RECREATE_DB_API_V3)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("code", containsString("COMMON-2100"))
                .body("reason", is("multi-cause error"))
                .body("errors[0].reason", is("Requested physical database is not registered"))
                .body("errors[0].message", is("Requested physical database is not registered. Identifier: " + physicalDbId))
                .body("status", is(String.valueOf(BAD_REQUEST.getStatusCode())))
                .body("@type", is(TYPE_V1_0));
    }

    @Test
    void recreateDbDoesNotExist() throws Exception {
        String physicalDbId = "123";
        Mockito.when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDbId)).thenReturn(new PhysicalDatabase());
        List<RecreateDatabaseRequest> request = createTestRecreateDatabaseRequest(PG_TYPE, testClassifier(), physicalDbId);
        Mockito.when(
                databaseDbaasRepository.getDatabaseByClassifierAndType(request.get(0).getClassifier(), request.get(0).getType())
        ).thenReturn(Optional.empty());
        Mockito.when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().post(RECREATE_DB_API_V3)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("code", containsString("COMMON-2100"))
                .body("reason", is("multi-cause error"))
                .body("errors[0].code", is(ErrorCodes.CORE_DBAAS_4006.getCode()))
                .body("errors[0].reason", is(ErrorCodes.CORE_DBAAS_4006.getTitle()))
                .body("errors[0].message", is(ErrorCodes.CORE_DBAAS_4006.getDetail(
                        request.get(0).getType(),
                        request.get(0).getClassifier())))
                .body("status", is(String.valueOf(BAD_REQUEST.getStatusCode())))
                .body("@type", is(TYPE_V1_0));
    }

    @Test
    void recreateDbErrorOccurred() throws Exception {
        String physicalDbId = "123";
        Mockito.when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDbId)).thenReturn(new PhysicalDatabase());
        List<RecreateDatabaseRequest> request = createTestRecreateDatabaseRequest(PG_TYPE, testClassifier(), physicalDbId);
        Database database = new Database();
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        database.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        Mockito.when(
                databaseDbaasRepository.getDatabaseByClassifierAndType(request.get(0).getClassifier(), request.get(0).getType())
        ).thenReturn(Optional.of(databaseRegistry));
        Mockito.when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        Mockito.when(dBaaService.recreateDatabase(any(), any())).thenThrow(new RuntimeException("I can't do it"));

        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().post(RECREATE_DB_API_V3)
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode())
                .body("meta.result.unsuccessfully[0].error", is("I can't do it"))
                .body("meta.result.successfully", empty());
    }

    @Test
    void recreateDbIsOk() throws Exception {
        String physicalDbId = "123";
        Mockito.when(physicalDatabaseDbaasRepository.findByPhysicalDatabaseIdentifier(physicalDbId)).thenReturn(new PhysicalDatabase());
        List<RecreateDatabaseRequest> request = createTestRecreateDatabaseRequest(PG_TYPE, testClassifier(), physicalDbId);
        Database db = new Database();
        db.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put(ROLE, Role.ADMIN.toString());
        }}));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(db);
        db.setDatabaseRegistry(Arrays.asList(databaseRegistry));
        Mockito.when(
                databaseDbaasRepository.getDatabaseByClassifierAndType(request.get(0).getClassifier(), request.get(0).getType())
        ).thenReturn(Optional.of(databaseRegistry));
        Mockito.when(dBaaService.isValidClassifierV3(any())).thenCallRealMethod();
        Mockito.when(dBaaService.recreateDatabase(any(), any())).thenReturn(databaseRegistry);
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().post(RECREATE_DB_API_V3)
                .then()
                .statusCode(OK.getStatusCode())
                .body("unsuccessfully", empty())
                .body("successfully", hasSize(1));
    }

    @Test
    void linkDatabases_200() throws Exception {
        Database db = new Database();
        db.setId(UUID.randomUUID());
        db.setAdapterId("adapter_id");
        DatabaseRegistry databaseRegistry = new DatabaseRegistry(db);
        Mockito.when(operationService.linkDbsToNamespace(any(), any())).thenReturn(List.of(databaseRegistry));
        Mockito.when(dBaaService.processConnectionPropertiesV3(any())).thenAnswer(inv ->
                new DatabaseResponseV3ListCP(inv.getArgument(0), null));
        LinkDatabasesRequest request = new LinkDatabasesRequest(List.of("ms"), "target");
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().post(LINK_DB_API_V3)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].id", equalTo(db.getId().toString()));
    }

    @Test
    void linkDatabases_400() throws Exception {
        LinkDatabasesRequest request = new LinkDatabasesRequest(List.of(), "");
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().post(LINK_DB_API_V3)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body(containsString(CORE_DBAAS_4044.getTitle()));
    }

    private List<RecreateDatabaseRequest> createTestRecreateDatabaseRequest(String type, Map<String, Object> classifier, String physicalDbId) {
        return Collections.singletonList(new RecreateDatabaseRequest(type, classifier, physicalDbId));
    }

    private UpdateConnectionPropertiesRequest createUpdateConnectionPropertiesRequest(Map<String, Object> classifier, Map<String, Object> connection, String role) {
        UpdateConnectionPropertiesRequest request = new UpdateConnectionPropertiesRequest();
        request.setConnectionProperties(connection);
        request.setClassifier(new TreeMap<>(classifier));
        return request;
    }

    private Map<String, Object> testClassifier() {
        Map<String, Object> classifier = new HashMap<>();
        classifier.put("microserviceName", "test-microservice");
        classifier.put("scope", "service");
        classifier.put("namespace", TEST_NAMESPACE);
        return classifier;
    }


    private String getUpdateClassifierUrl(String type) {
        return String.format("/databases/update-classifier/%s", type);
    }

    private String getUpdateConnectionPropertiesUrl(String type) {
        return String.format("/databases/update-connection/%s", type);
    }

    private UpdateClassifierRequestV3 createUpdateClassifierRequest(Map<String, Object> primaryClassifier, Map<String, Object> targetClassifier) {
        UpdateClassifierRequestV3 updateClassifierRequest = new UpdateClassifierRequestV3();
        updateClassifierRequest.setTo(new TreeMap<>(targetClassifier));
        updateClassifierRequest.setFrom(new TreeMap<>(primaryClassifier));
        return updateClassifierRequest;
    }

    private void performRequest(String url, String namespace, Method method, Object requestBody, int expectStatus) throws Exception {
        log.info("Request url {} method {}, request body {}, expectStatus {}", url, method, requestBody, expectStatus);

        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, namespace)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(requestBody))
                .when().request(method, url)
                .then()
                .statusCode(expectStatus);
    }

    private void performUpdateConnPropertiesRequest(UpdateConnectionPropertiesRequest request, String type, String reason, String message) throws Exception {
        given().auth().preemptive().basic("dbaas-db-editor-client", "dbaas-db-editor-client")
                .pathParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().put(UPD_CP_API_V3, type)
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .body("code", containsString("COMMON-2100"))
                .body("reason", is("multi-cause error"))
                .body("errors[0].reason", is(reason))
                .body("errors[0].message", is(message))
                .body("status", is(String.valueOf(BAD_REQUEST.getStatusCode())))
                .body("@type", is(TYPE_V1_0));
    }

    private PasswordChangeRequestV3 createPasswordChangeRequest(Map<String, Object> classifier, String type) {
        PasswordChangeRequestV3 passwordChangeRequest = new PasswordChangeRequestV3();
        passwordChangeRequest.setUserRole(Role.ADMIN.toString());
        passwordChangeRequest.setClassifier(classifier);
        passwordChangeRequest.setType(type);
        return passwordChangeRequest;
    }

    private Map<String, Object> createTestConnectionProperties(String role) {
        return new HashMap<String, Object>() {{
            put("url", "http://test-url");
            put("username", "test");
            put("role", role);
        }};
    }

    private Map<String, Object> createTestConnectionPropertiesWithCorrectEncryptedPassword(String role) {
        return new HashMap<String, Object>() {{
            put("url", "http://test-url");
            put("username", "test");
            put("role", role);
            put("encryptedPassword", "{v2c}{AES}{DEFAULT_KEY}{BmJWW/qsyfgN7EisgfjOaLHc+EOs7S1MYwB87sv4325/L/zojG7u7RDUjFa3K9t/}");
        }};
    }

    private Map<String, Object> createTestConnectionPropertiesWithInCorrectEncryptedPassword(String role) {
        return new HashMap<String, Object>() {{
            put("url", "http://test-url");
            put("username", "test");
            put("role", role);
            put("encryptedPassword", "abcd");
        }};
    }
}
