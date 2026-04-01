package com.netcracker.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.dto.CleanupMarkedForDropRequest;
import com.netcracker.cloud.dbaas.dto.role.Role;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.integration.config.PostgresqlContainerResource;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import com.netcracker.cloud.dbaas.service.DBaaService;
import com.netcracker.cloud.dbaas.service.DbaaSHelper;
import com.netcracker.cloud.dbaas.service.DeletionService;
import com.netcracker.cloud.dbaas.service.ProcessConnectionPropertiesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.netcracker.cloud.dbaas.Constants.ROLE;
import static com.netcracker.cloud.dbaas.DbaasApiPath.FIND_BY_NAME_PATH;
import static com.netcracker.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.*;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(AggregatedDatabaseAdministrationNoNamespaceControllerV3.class)
@Slf4j
class AggregatedDatabaseAdministrationNoNamespaceControllerV3Test {

    private static final String TEST_NAME = "test-db";
    private static final String PHYSICAL_DATABASE_ID = "some_physical_database_id";
    private static final String TEST_NAMESPACE = "test-namespace";

    @InjectMock
    DatabaseRegistryDbaasRepository databaseRegistryDbaasRepository;
    @InjectMock
    DatabaseDbaasRepository databaseDbaasRepository;
    @InjectMock
    DBaaService dBaaService;
    @InjectMock
    DbaaSHelper dbaaSHelper;
    @InjectMock
    ProcessConnectionPropertiesService connectionPropertiesService;
    @InjectMock
    DeletionService deletionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testGetDatabasesByNamespaceAndName() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(TEST_NAME, TEST_NAMESPACE)).thenReturn(singletonList(database));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .queryParam(NAMESPACE_PARAMETER, TEST_NAMESPACE)
                .when().get(FIND_BY_NAME_PATH, TEST_NAME)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()));
    }

    @Test
    void testGetDatabasesByName() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(TEST_NAME, null)).thenReturn(singletonList(database));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get(FIND_BY_NAME_PATH, TEST_NAME)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()));
    }

    @Test
    void testGetDatabasesByNameWithDecryptedPassword() {
        when(dBaaService.getConnectionPropertiesService()).thenReturn(connectionPropertiesService);
        final DatabaseRegistry database = getDatabaseSample();
        when(databaseRegistryDbaasRepository.findAnyLogDbTypeByNameAndOptionalParams(TEST_NAME, null)).thenReturn(singletonList(database));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .queryParam("withDecryptedPassword", true)
                .when().get(FIND_BY_NAME_PATH, TEST_NAME)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].name", is(database.getName()));
    }

    @Test
    void testDeleteAll_ProdModeTrue() {
        when(dbaaSHelper.isProductionMode()).thenReturn(true);
        Set<String> namespaces = Set.of("ns1", "ns2");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .queryParam("namespaces", namespaces)
                .when().delete("/deleteall")
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
        verifyNoInteractions(deletionService);
    }

    @Test
    void testDeleteAll_ProdModeFalse_Empty() {
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        when(databaseDbaasRepository.countByNamespaces(any())).thenReturn(0L);
        Set<String> namespaces = Set.of("ns1", "ns2");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .queryParam("namespaces", namespaces)
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());
        verifyNoInteractions(deletionService);
    }

    @Test
    void testDeleteAll_ProdModeFalse_NotEmpty() {
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        when(databaseDbaasRepository.countByNamespaces(any())).thenReturn(1L);
        Set<String> namespaces = Set.of("ns1", "ns2");
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .queryParam("namespaces", namespaces)
                .when().delete("/deleteall")
                .then()
                .statusCode(OK.getStatusCode());
        verify(deletionService).cleanupAllLogicalDatabasesInNamespacesByPortionsAsync(anySet());
    }

    @Test
    void testGetMarkedForDropDatabases() throws JsonProcessingException {
        List<String> namespaces = List.of("ns1", "ns2");
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        database.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(database);
        List<DatabaseRegistry> registries = List.of(databaseRegistry);
        when(deletionService.getMarkedForDropRegistries(argThat((List<String> argument) -> argument.containsAll(namespaces)))).thenReturn(registries);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(namespaces))
                .when().post("/marked-for-drop")
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].id", is(databaseRegistry.getDatabase().getId().toString()));
        verify(deletionService).getMarkedForDropRegistries(argThat((List<String> argument) -> argument.containsAll(namespaces)));
    }

    @Test
    void testCleanupMarkedForDropDatabases_ProdModeTrue() throws JsonProcessingException {
        CleanupMarkedForDropRequest request = new CleanupMarkedForDropRequest();
        request.setNamespaces(List.of("ns1", "ns2"));
        when(dbaaSHelper.isProductionMode()).thenReturn(true);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().delete("/marked-for-drop")
                .then()
                .statusCode(FORBIDDEN.getStatusCode());
        verifyNoInteractions(deletionService);
    }

    @Test
    void testCleanupMarkedForDropDatabases_ProdModeFalse_DeleteFalse() throws JsonProcessingException {
        CleanupMarkedForDropRequest request = new CleanupMarkedForDropRequest();
        request.setNamespaces(List.of("ns1", "ns2"));
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        database.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(database);
        List<DatabaseRegistry> registries = List.of(databaseRegistry);
        when(deletionService.getMarkedForDropRegistries(argThat((List<String> argument) -> argument.containsAll(request.getNamespaces())))).thenReturn(registries);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().delete("/marked-for-drop")
                .then()
                .statusCode(OK.getStatusCode());
        verify(deletionService).getMarkedForDropRegistries(argThat((List<String> argument) -> argument.containsAll(request.getNamespaces())));
        verifyNoMoreInteractions(deletionService);
    }

    @Test
    void testCleanupMarkedForDropDatabases_ProdModeFalse_DeleteTrue() throws JsonProcessingException {
        CleanupMarkedForDropRequest request = new CleanupMarkedForDropRequest();
        request.setNamespaces(List.of("ns1", "ns2"));
        request.setDelete(true);
        when(dbaaSHelper.isProductionMode()).thenReturn(false);
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        Database database = new Database();
        database.setId(UUID.randomUUID());
        databaseRegistry.setDatabase(database);
        List<DatabaseRegistry> registries = List.of(databaseRegistry);
        when(deletionService.getMarkedForDropRegistries(argThat((List<String> argument) -> argument.containsAll(request.getNamespaces())))).thenReturn(registries);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(request))
                .when().delete("/marked-for-drop")
                .then()
                .statusCode(ACCEPTED.getStatusCode());
        verify(deletionService).getMarkedForDropRegistries(argThat((List<String> argument) -> argument.containsAll(request.getNamespaces())));
        for (String namespace : request.getNamespaces()) {
            verify(deletionService).cleanupMarkedForDropRegistriesAsync(eq(namespace), eq(request.getForce()));
        }
        verifyNoMoreInteractions(deletionService);
    }

    private DatabaseRegistry getDatabaseSample() {
        final Database database = new Database();
        database.setName(TEST_NAME);
        HashMap<String, Object> cp = new HashMap<>();
        cp.put(ROLE, Role.ADMIN.toString());
        database.setConnectionProperties(singletonList(cp));
        database.setResources(Collections.singletonList(new DbResource("database", TEST_NAME)));

        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(database);
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);
        return databaseRegistry;
    }
}
