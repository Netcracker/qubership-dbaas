package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.DatabaseResponseV3ListCP;
import org.qubership.cloud.dbaas.dto.v3.UpdateHostRequest;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.DbState;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static io.quarkus.datasource.common.runtime.DatabaseKind.POSTGRESQL;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DatabaseOperationGlobalControllerV3.class)
class DatabaseOperationGlobalControllerV3Test {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMock
    OperationService operationService;

    @InjectMock
    PhysicalDatabasesService physicalDatabasesService;

    @InjectMock
    DBaaService dBaaService;

    @InjectMock
    UserService userService;

    @Test
    void updateHost_200() throws JsonProcessingException {
        UpdateHostRequest updateHostRequest = new UpdateHostRequest();
        updateHostRequest.setClassifier(getClassifier());
        updateHostRequest.setType(POSTGRESQL);
        updateHostRequest.setMakeCopy(false);
        updateHostRequest.setPhysicalDatabaseId("destination-physical-id");
        updateHostRequest.setPhysicalDatabaseHost("pg-patroni.destination-pg");
        Mockito.when(userService.findUsersByDatabase(any())).thenReturn(Collections.emptyList());
        Mockito.when(dBaaService.getConnectionPropertiesService()).thenReturn(Mockito.mock(ProcessConnectionPropertiesService.class));
        Mockito.when(operationService.changeHost(anyList())).thenReturn(List.of(createDatabase()));

        Response response = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(List.of(updateHostRequest)))
                .accept(MediaType.APPLICATION_JSON)
                .post("/update-host");

        assertEquals(200, response.getStatusCode());

        List<DatabaseResponseV3ListCP> databaseResponse = objectMapper.readValue(response.getBody().print(), new TypeReference<List<DatabaseResponseV3ListCP>>() {
        });

        assertEquals("destination-physical-id", databaseResponse.getFirst().getPhysicalDatabaseId());
    }

    @Test
    void updateHost_InvalidRequest_400() throws JsonProcessingException {
        UpdateHostRequest updateHostRequest = new UpdateHostRequest();
        updateHostRequest.setClassifier(getClassifier());
        updateHostRequest.setType(POSTGRESQL);
        // Missing required physicalDatabaseId and physicalDatabaseHost

        Response response = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(List.of(updateHostRequest)))
                .accept(MediaType.APPLICATION_JSON)
                .post("/update-host");

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void updateHost_MissingType_400() throws JsonProcessingException {
        UpdateHostRequest updateHostRequest = new UpdateHostRequest();
        updateHostRequest.setClassifier(getClassifier());
        updateHostRequest.setPhysicalDatabaseId("destination-physical-id");
        updateHostRequest.setPhysicalDatabaseHost("pg-patroni.destination-pg");
        // Missing type

        Response response = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(List.of(updateHostRequest)))
                .accept(MediaType.APPLICATION_JSON)
                .post("/update-host");

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void updateHost_EmptyClassifier_400() throws JsonProcessingException {
        UpdateHostRequest updateHostRequest = new UpdateHostRequest();
        updateHostRequest.setType(POSTGRESQL);
        updateHostRequest.setPhysicalDatabaseId("destination-physical-id");
        updateHostRequest.setPhysicalDatabaseHost("pg-patroni.destination-pg");
        updateHostRequest.setClassifier(Collections.emptyMap()); // Empty classifier

        Response response = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(List.of(updateHostRequest)))
                .accept(MediaType.APPLICATION_JSON)
                .post("/update-host");

        assertEquals(400, response.getStatusCode());
    }


    private static SortedMap<String, Object> getClassifier() {
        SortedMap<String, Object> classifier = new TreeMap<>();
        classifier.put("microserviceName", "test-service");
        classifier.put("namespace", "test-namespace");
        classifier.put("scope", "service");
        return classifier;
    }

    private DatabaseRegistry createDatabase() {
        Database database = new Database();
        database.setId(UUID.randomUUID());
        SortedMap<String, Object> classifier = getClassifier();
        database.setClassifier(classifier);
        database.setType(POSTGRESQL);
        database.setNamespace((String) classifier.get("namespace"));
        database.setConnectionProperties(Arrays.asList(new HashMap<String, Object>() {{
            put(ROLE, Role.ADMIN.toString());
            put("port", 5432);
            put("host", "pg-patroni.destination-pg");
            put("name", "dbaas_d11b5fd935e548a6bf8574d35db45555");
            put("url", "jdbc:postgresql://pg-patroni.destination-pg:5432/dbaas_d11b5fd935e548a6bf8574d35db45555");
            put("username", "dbaas_62f293eec4484af1a89171c5da70e769");
            put("encryptedPassword", "{v2c}{AES}{DEFAULT_KEY}{BmJWW/qsyfgN7EisgfjOaLHc+EOs7S1MYwB87sv4325/L/zojG7u7RDUjFa3K9t/}");
        }}));

        ArrayList<DatabaseRegistry> databaseRegistries = new ArrayList<>();
        DatabaseRegistry databaseRegistry = createDatabaseRegistry();
        databaseRegistry.setDatabase(database);
        databaseRegistries.add(databaseRegistry);
        database.setDatabaseRegistry(databaseRegistries);

        DbResource resource = new DbResource("someKind", "someName");
        List<DbResource> resources = new ArrayList<>();
        resources.add(resource);
        database.setResources(resources);
        database.setName("dbaas_d11b5fd935e548a6bf8574d35db45555");
        database.setAdapterId("c53854b3-d3f2-4b4d-b0b4-bde49b70bcc0");
        database.setPhysicalDatabaseId("destination-physical-id");
        database.setDbState(new DbState(DbState.DatabaseStateStatus.CREATED));
        return databaseRegistry;
    }

    private DatabaseRegistry createDatabaseRegistry() {
        DatabaseRegistry databaseRegistry = new DatabaseRegistry();
        SortedMap<String, Object> classifier = getClassifier();
        databaseRegistry.setClassifier(classifier);
        databaseRegistry.setType(POSTGRESQL);
        databaseRegistry.setNamespace((String) classifier.get("namespace"));
        return databaseRegistry;
    }
}