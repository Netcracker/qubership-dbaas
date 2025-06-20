package org.qubership.cloud.dbaas.controller.v3;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.repositories.dbaas.DatabaseRegistryDbaasRepository;
import org.qubership.cloud.dbaas.service.DBaaService;
import org.qubership.cloud.dbaas.service.ProcessConnectionPropertiesService;
import org.qubership.cloud.dbaas.service.UserService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.FIND_BY_NAME_PATH;
import static org.qubership.cloud.dbaas.DbaasApiPath.NAMESPACE_PARAMETER;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.OK;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

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
    UserService userService;
    @InjectMock
    DBaaService dBaaService;
    @InjectMock
    ProcessConnectionPropertiesService connectionPropertiesService;

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