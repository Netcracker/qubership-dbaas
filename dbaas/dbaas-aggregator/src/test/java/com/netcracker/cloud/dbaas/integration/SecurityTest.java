package com.netcracker.cloud.dbaas.integration;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.ClassifierWithRolesRequest;
import com.netcracker.cloud.dbaas.dto.HttpBasicCredentials;
import com.netcracker.cloud.dbaas.dto.UpdateConnectionPropertiesRequest;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.dto.v3.CreatedDatabaseV3;
import com.netcracker.cloud.dbaas.dto.v3.UpdateClassifierRequestV3;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.ExternalAdapterRegistrationEntry;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.integration.config.MockOidcTestResource;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.integration.config.SecurityTestProfile;
import com.netcracker.cloud.dbaas.integration.utils.TestJwtUtils;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.BgNamespaceRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.DatabaseDeclarativeConfigRepository;
import com.netcracker.cloud.dbaas.service.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.MockitoConfig;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.DbaasApiPath.DATABASE_OPERATION_PATH_V3;
import static com.netcracker.cloud.dbaas.DbaasApiPath.DBAAS_PATH_V3;
import static com.netcracker.cloud.dbaas.DbaasApiPath.LIST_DATABASES_PATH;
import static com.netcracker.cloud.dbaas.DbaasApiPath.VERSION_2;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.OK;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@QuarkusTestResource(MockOidcTestResource.class)
@TestProfile(SecurityTestProfile.class)
class SecurityTest {

    private static final String TEST_TYPE = "mongodbtest";
    private static final String TESTADAPTERID = "testadapterid";

    @InjectMock
    DbaasAdapterRESTClientFactory dbaasAdapterRESTClientFactory;
    DbaasAdapter testDbaasAdapter;
    @InjectMock
    @MockitoConfig(convertScopes = true)
    CryptoServicePasswordEncryption cryptoServicePasswordEncryption;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;
    @InjectMock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @InjectMock
    DatabaseDbaasRepository databaseDbaasRepository;
    @InjectMock
    BgNamespaceRepository bgNamespaceRepository;
    @InjectMock
    DatabaseDeclarativeConfigRepository declarativeConfigRepository;
    @InjectMock
    DbaaSHelper dbaaSHelper;
    @InjectMock
    ProcessConnectionPropertiesService processConnectionPropertiesService;
    @Inject
    PasswordEncryption passwordEncryption;
    @Inject
    TestJwtUtils jwtUtils;

    @BeforeEach
    void prepareMock() {
        testDbaasAdapter = mock(DbaasAdapter.class);
        when(dbaasAdapterRESTClientFactory.createDbaasAdapterClientV2(any(), any(), any(), any(), any(), any(), any())).thenReturn(testDbaasAdapter);
        CreatedDatabaseV3 testDatabase = new CreatedDatabaseV3();
        testDatabase.setAdapterId(TESTADAPTERID);
        testDatabase.setName("testdbname");
        testDatabase.setConnectionProperties(List.of(new HashMap<>() {{
            put(ROLE, Role.ADMIN.toString());
        }}));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId("some_id");
        when(physicalDatabasesService.getByAdapterId(any())).thenReturn(physicalDatabase);

        when(testDbaasAdapter.type()).thenReturn(TEST_TYPE);
        when(testDbaasAdapter.identifier()).thenReturn(TESTADAPTERID);
        when(testDbaasAdapter.createDatabaseV3(any(), any()))
                .thenReturn(testDatabase);
        when(testDbaasAdapter.getSupportedVersion())
                .thenReturn("v2");
        PhysicalDatabase mockPhysicalDatabase = new PhysicalDatabase();
        mockPhysicalDatabase.setAdapter(new ExternalAdapterRegistrationEntry(TESTADAPTERID, "", new HttpBasicCredentials("username", "password"), VERSION_2, null));
        when(physicalDatabasesService.balanceByType(any())).thenReturn(mockPhysicalDatabase);
        when(physicalDatabasesService.getAdapterById(any())).thenReturn(testDbaasAdapter);
        DatabaseRegistry[] savedDatabase = new DatabaseRegistry[1];
        when(databaseRegistryDbaasRepository.saveAnyTypeLogDb(any())).then(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.saveInternalDatabase(any())).then(invocationOnMock -> {
            savedDatabase[0] = invocationOnMock.getArgument(0);
            return savedDatabase[0];
        });
        when(databaseRegistryDbaasRepository.findDatabaseRegistryById(any())).then(invocationOnMock -> Optional.of(savedDatabase[0]));
        Database getByClassifierDatabase = new Database();
        getByClassifierDatabase.setId(UUID.randomUUID());
        Map<String, Object> connectionProperties = new HashMap<>();
        connectionProperties.put("password", "cGFzc3dvcmQ=");
        connectionProperties.put(ROLE, Role.ADMIN.toString());
        SortedMap<String, Object> classifier = new TreeMap<String, Object>() {{
            put("namespace", "unit-test-namespace");
        }};
        getByClassifierDatabase.setClassifier(classifier);
        getByClassifierDatabase.setConnectionProperties(Arrays.asList(connectionProperties));
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setId(UUID.randomUUID());
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setDatabase(getByClassifierDatabase);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        getByClassifierDatabase.setDatabaseRegistry(databaseRegistries);
        when(databaseRegistryDbaasRepository.getDatabaseByClassifierAndType(any(), any())).thenReturn(Optional.of(getByClassifierDatabase.getDatabaseRegistry().get(0)));
        when(databaseRegistryDbaasRepository.getDatabaseByOldClassifierAndType(any(), any())).thenReturn(getByClassifierDatabase.getDatabaseRegistry().get(0));

        when(dbaaSHelper.isProductionMode()).thenReturn(false);
    }

    @Test
    void testClusterDbaCanAccessCreate() {
        createDatabase("cluster-dba", "someDefaultPassword")
                .statusCode(CREATED.getStatusCode());
    }

    @Test
    void testClusterDbaCanAccessNamespaceClean() {
        cleanNamespace("cluster-dba", "someDefaultPassword")
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testWithoutRolesCannotAccessCreate() {
        createDatabase("test_without_roles", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testWithoutRolesCannotAccessNamespaceClean() {
        cleanNamespace("test_without_roles", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testOnlyNamespaceCleanerCannotAccessCreate() {
        createDatabase("test_only_namespace_cleaner", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testOnlyNamespaceCleanerCanAccessNamespaceClean() {
        cleanNamespace("test_only_namespace_cleaner", "someDefaultPassword")
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testOnlyNamespaceCleanerCanAccessNamespaceCleanV3() {
        cleanNamespaceV3("test_only_namespace_cleaner", "someDefaultPassword")
                .statusCode(OK.getStatusCode());
    }

    @Test
    void testOnlyDbClientCannotAccessNamespaceClean() {
        cleanNamespace("test_only_db_client", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testOnlyDbClientCanAccessCreate() {
        createDatabase("test_only_db_client", "someDefaultPassword")
                .statusCode(CREATED.getStatusCode());
    }

    @Test
    void testUpdateClassifier() throws JsonProcessingException {
        updateClassifier("dbaas-db-editor", "editor_password", new UpdateClassifierRequestV3(), "test-namespace", "mongodb")
                .statusCode(not(UNAUTHORIZED.getStatusCode()));
        updateClassifier("test_only_namespace_cleaner", "someDefaultPassword", new UpdateClassifierRequestV3(), "test-namespace", "mongodb")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testUpdateConnectionProperties() throws JsonProcessingException {
        updateConnectionProperties("dbaas-db-editor", "editor_password", new UpdateConnectionPropertiesRequest(), "test-namespace", "mongodb")
                .statusCode(not(UNAUTHORIZED.getStatusCode()));
        updateConnectionProperties("cluster-dba", "someDefaultPassword", new UpdateConnectionPropertiesRequest(), "test-namespace", "mongodb")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testRecreateDatabaseForbidenForClusterDBA() {
        recreateDb("cluster-dba", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testDbaaSHttpFirewall() {
        given().auth().preemptive().basic("test_only_db_client", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"" + TEST_TYPE + "\", \"classifier\":{\"scope\":\"service\", \"microserviceName\":\"test-name\", \"namespace\":\"unit-test-namespace\"}, \"originService\":\"test-name\"}")
                .accept(MediaType.APPLICATION_JSON)
                .urlEncodingEnabled(false)
                .put("//api/v3/dbaas/unit-test-namespace/databases")
                .then()
                .statusCode(CREATED.getStatusCode());
    }

    @Test
    void testDiscrToolUserCannotAccessWriteOperations() {
        createDatabase("discr-tool-user", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
        cleanNamespace("discr-tool-user", "someDefaultPassword")
                .statusCode(FORBIDDEN.getStatusCode());
    }

    @Test
    void testDiscrToolUserCanAccessListDatabases() {
        listDatabases("discr-tool-user", "someDefaultPassword").statusCode(OK.getStatusCode());
    }

    @Test
    void testDiscrToolUserCanAccessGetByClassifier() throws JsonProcessingException {
        Map<String, Object> classifier = new TreeMap() {{
            put("namespace", "unit-test-namespace");
            put("scope", "service");
            put("microserviceName", "test-name");
        }};
        getByClassifier("discr-tool-user", "someDefaultPassword", classifier).statusCode(OK.getStatusCode());
    }

    @Test
    void testCreateDatabaseWithKubernetesToken() {
        createDatabaseWithKubernetesToken(jwtUtils.getJwt("test-name", "unit-test-namespace"))
                .statusCode(CREATED.getStatusCode());
    }

    @Test
    void testCreateDatabaseWithInvalidKubernetesToken() {
        createDatabaseWithKubernetesToken(jwtUtils.getJwt("test-name", "unit-test-namespace")+"pad-to-make-invalid-signature")
                .statusCode(UNAUTHORIZED.getStatusCode());
    }

    private ValidatableResponse updateClassifier(String user, String password, UpdateClassifierRequestV3 updateClassifierRequest, String namespace, String type) throws JsonProcessingException {
        return given().auth().preemptive().basic(user, password)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(updateClassifierRequest))
                .accept(MediaType.APPLICATION_JSON)
                .put(DBAAS_PATH_V3 + "/namespaces/{namespace}/databases/update-classifier/{type}", namespace, type)
                .then();
    }

    private ValidatableResponse updateConnectionProperties(String user, String password, UpdateConnectionPropertiesRequest updateRequest, String namespace, String type) throws JsonProcessingException {
        return given().auth().preemptive().basic(user, password)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(updateRequest))
                .accept(MediaType.APPLICATION_JSON)
                .put(DBAAS_PATH_V3 + "/namespaces/{namespace}/databases/update-connection/{type}", namespace, type)
                .then();
    }

    private ValidatableResponse createDatabase(String user, String password) {
        return given().auth().preemptive().basic(user, password)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"" + TEST_TYPE + "\", \"classifier\":{\"scope\":\"service\", \"microserviceName\":\"test-name\", \"namespace\":\"unit-test-namespace\"}, \"originService\":\"test-name\"}")
                .accept(MediaType.APPLICATION_JSON)
                .put(DBAAS_PATH_V3 + "/unit-test-namespace/databases")
                .then();
    }

    private ValidatableResponse createDatabaseWithKubernetesToken(String token) {
        return given().auth().preemptive().oauth2(token)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"" + TEST_TYPE + "\", \"classifier\":{\"scope\":\"service\", \"microserviceName\":\"test-name\", \"namespace\":\"unit-test-namespace\"}, \"originService\":\"test-name\"}")
                .accept(MediaType.APPLICATION_JSON)
                .put(DBAAS_PATH_V3 + "/unit-test-namespace/databases")
                .then();
    }


    private ValidatableResponse cleanNamespace(String user, String password) {
        return given().auth().preemptive().basic(user, password)
                .delete(DBAAS_PATH_V3 + "/unit-test-namespace/databases")
                .then();
    }


    private ValidatableResponse cleanNamespaceV3(String user, String password) {
        return given().auth().preemptive().basic(user, password)
                .delete(DBAAS_PATH_V3 + "/unit-test-namespace/databases/deleteall")
                .then();
    }

    private ValidatableResponse listDatabases(String user, String password) {
        return given().auth().preemptive().basic(user, password)
                .accept(MediaType.APPLICATION_JSON)
                .get(DBAAS_PATH_V3 + "/unit-test-namespace/databases" + LIST_DATABASES_PATH)
                .then();
    }

    private ValidatableResponse getByClassifier(String user, String password, Map<String, Object> classifier) throws JsonProcessingException {
        ClassifierWithRolesRequest request = new ClassifierWithRolesRequest();
        request.setClassifier(classifier);
        request.setOriginService(classifier.get("microserviceName").toString());
        return given().auth().preemptive().basic(user, password)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(request))
                .accept(MediaType.APPLICATION_JSON)
                .get(DBAAS_PATH_V3 + "/unit-test-namespace/databases" + LIST_DATABASES_PATH)
                .then();
    }

    private ValidatableResponse recreateDb(String user, String password) {
        return given().auth().preemptive().basic(user, password)
                .contentType(MediaType.APPLICATION_JSON)
                .body("[\n" +
                        "    {\n" +
                        "        \"type\": \"postgresql\",\n" +
                        "        \"classifier\": {\n" +
                        "            \"isServiceDb\": true,\n" +
                        "            \"microserviceName\": \"Service-test\",\n" +
                        "            \"namespace\": \"phys-db-migration\"\n" +
                        "        },\n" +
                        "        \"physicalDatabaseId\": \"123456\"\n" +
                        "    }\n" +
                        "]")
                .accept(MediaType.APPLICATION_JSON)
                .post(DATABASE_OPERATION_PATH_V3 + "/databases/recreate", "unit-test-namespace")
                .then();
    }

    private String requestBody(Object content) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.writeValueAsString(content);
    }
}
