package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.RestorePasswordRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.DbaasAdapter;
import org.qubership.cloud.dbaas.service.DbaasAdapterRESTClientV2;
import org.qubership.cloud.dbaas.service.PhysicalDatabasesService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.ACCEPTED;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Slf4j
@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(InternalOperationController.class)
class InternalOperationControllerTest {

    @InjectMock
    DBaaService dBaaService;
    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSuccessfulResponseDuringRestore() throws Exception {
        when(physicalDatabasesService.getDatabasesByPhysDbAndType("core-opensearch", "opensearch"))
                .thenReturn(List.of(createTestDb()));
        Map<String, Object> testCP = new HashMap<>() {{
            put("username", "value1");
            put("password", "value2");
        }};
        DatabaseResponseV3ListCP response = new DatabaseResponseV3ListCP();
        response.setConnectionProperties(List.of(testCP));

        DbaasAdapter adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.restorePasswords(any(), any())).thenReturn(ACCEPTED);
        when(physicalDatabasesService.getAdapterByPhysDbId("core-opensearch")).thenReturn(adapter);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(createRestorePasswordRequest()))
                .when().post("/users/restore-password")
                .then()
                .statusCode(ACCEPTED.getStatusCode());
    }

    @Test
    void testNoDatabasesForRestore() throws Exception {
        when(physicalDatabasesService.getDatabasesByPhysDbAndType("core-opensearch", "opensearch"))
                .thenReturn(Collections.emptyList());

        DbaasAdapter adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.restorePasswords(any(), any())).thenReturn(ACCEPTED);
        when(physicalDatabasesService.getAdapterByPhysDbId("core-opensearch")).thenReturn(adapter);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(createRestorePasswordRequest()))
                .when().post("/users/restore-password")
                .then()
                .statusCode(ACCEPTED.getStatusCode());
    }

    @Test
    void testUnsuccessfulResponseDuringRestore() throws Exception {
        when(physicalDatabasesService.getDatabasesByPhysDbAndType("core-opensearch", "opensearch"))
                .thenReturn(List.of(createTestDb()));
        Map<String, Object> testCP = new HashMap<>() {{
            put("username", "value1");
            put("password", "value2");
        }};
        DatabaseResponseV3ListCP response = new DatabaseResponseV3ListCP();
        response.setConnectionProperties(List.of(testCP));

        DbaasAdapter adapter = Mockito.mock(DbaasAdapterRESTClientV2.class);
        when(adapter.restorePasswords(any(), any())).thenReturn(INTERNAL_SERVER_ERROR);
        when(physicalDatabasesService.getAdapterByPhysDbId("core-opensearch")).thenReturn(adapter);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(createRestorePasswordRequest()))
                .when().post("/users/restore-password")
                .then()
                .statusCode(INTERNAL_SERVER_ERROR.getStatusCode());
    }

    private RestorePasswordRequest createRestorePasswordRequest() {
        RestorePasswordRequest request = new RestorePasswordRequest();
        request.setSettings(Collections.singletonMap("trackId", "test-track-id"));
        request.setType("opensearch");
        request.setPhysicalDbId("core-opensearch");
        return request;
    }

    private Database createTestDb() {
        Database db = new Database();
        db.setId(UUID.randomUUID());
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        databaseRegistry.setDatabase(db);
        databaseRegistry.setConnectionProperties(new ArrayList<>());
        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<DatabaseRegistry>();
        databaseRegistries.add(databaseRegistry);
        db.setDatabaseRegistry(databaseRegistries);
        return db;
    }
}