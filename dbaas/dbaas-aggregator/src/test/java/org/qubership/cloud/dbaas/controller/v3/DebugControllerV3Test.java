package org.qubership.cloud.dbaas.controller.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.dto.v3.*;
import org.qubership.cloud.dbaas.entity.pg.Database;
import org.qubership.cloud.dbaas.entity.pg.DatabaseRegistry;
import org.qubership.cloud.dbaas.entity.pg.DbResource;
import org.qubership.cloud.dbaas.entity.pg.PhysicalDatabase;
import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.service.DebugService;
import cz.jirutka.rsql.parser.RSQLParserException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.DbaasApiPath.*;
import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.OK;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(DebugControllerV3.class)
class DebugControllerV3Test {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String PHYSICAL_DATABASE_ID = "test-phdbid";
    private final String TEST_NAME = "test-name";

    @InjectSpy
    DebugService debugService;

    @BeforeEach
    void setUp() {
        Mockito.reset(debugService);
    }

    @Test
    void testGetDumpOfDbaasDatabaseInformationInJsonFormat() throws JsonProcessingException {
        var expectedDumpResponse = createEmptyDumpResponse();
        var expectedDumpResponseStr = objectMapper.writeValueAsString(expectedDumpResponse);

        Mockito.doReturn(expectedDumpResponse).when(debugService).loadDumpV3();

        var actualDumpResponseStr = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
            .accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_JSON)
            .when().get("/dump")
            .then()
            .statusCode(200)
            .contentType(MediaType.APPLICATION_JSON)
            .extract().asString();

        Assertions.assertEquals(expectedDumpResponseStr, actualDumpResponseStr);

        Mockito.verify(debugService, Mockito.times(1)).loadDumpV3();
        Mockito.verifyNoMoreInteractions(debugService);
    }

    @Test
    void testGetDumpOfDbaasDatabaseInformationInOctetStreamFormat() {
        var expectedDumpResponse = createEmptyDumpResponse();

        Mockito.doReturn(expectedDumpResponse).when(debugService).loadDumpV3();

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .when().get("/dump")
            .then()
            .statusCode(200)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, DebugControllerV3.DUMP_ZIP_CONTENT_DISPOSITION_HEADER_VALUE)
            .extract().asByteArray();

        Mockito.verify(debugService, Mockito.times(1))
            .loadDumpV3();
        Mockito.verify(debugService, Mockito.times(1))
            .getStreamingOutputSerializingDumpToZippedJsonFile(expectedDumpResponse);

        Mockito.verifyNoMoreInteractions(debugService);
    }

    @Test
    void testDebugGetLogicalDatabasesReturnsBadRequestErrorCodeWhenRSQLParserExceptionIsThrown() {
        var invalidFilterQueryParameterValue = ";name==LogicalDb123";

        Mockito.doThrow(RSQLParserException.class)
            .when(debugService).findDebugLogicalDatabases(invalidFilterQueryParameterValue);

        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
            .contentType(MediaType.APPLICATION_JSON)
            .queryParam("filter", invalidFilterQueryParameterValue)
            .when().get("/databases")
            .then()
            .statusCode(Response.Status.BAD_REQUEST.getStatusCode()).extract().asString();
    }

    @Test
    void testDebugGetLogicalDatabasesWithNullFilterQueryParameterValue() throws JsonProcessingException {
        doTestDebugGetLogicalDatabasesWithFilterQueryParameterValue(null);
    }

    @Test
    void testDebugGetLogicalDatabasesWithFilledFilterQueryParameterValue() throws JsonProcessingException {
        var filterQueryParameterValue = "name==LogicalDb123;roles=in=(\"rw\")";

        doTestDebugGetLogicalDatabasesWithFilterQueryParameterValue(filterQueryParameterValue);
    }

    protected void doTestDebugGetLogicalDatabasesWithFilterQueryParameterValue(String filterQueryParameterValue) throws JsonProcessingException {
        var expectedDebugLogicalDatabases = List.of(createEmptyDebugLogicalDatabase());
        var expectedDebugLogicalDatabasesStr = objectMapper.writeValueAsString(expectedDebugLogicalDatabases);

        Mockito.doReturn(expectedDebugLogicalDatabases)
            .when(debugService).findDebugLogicalDatabases(filterQueryParameterValue);

        var requestSpecification = given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
            .contentType(MediaType.APPLICATION_JSON);

        if (filterQueryParameterValue != null) {
            requestSpecification.queryParam("filter", filterQueryParameterValue);
        }

        var actualDebugLogicalDatabasesStr = requestSpecification
            .when().get("/databases")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .extract().asString();

        Assertions.assertEquals(expectedDebugLogicalDatabasesStr, actualDebugLogicalDatabasesStr);

        Mockito.verify(debugService).findDebugLogicalDatabases(filterQueryParameterValue);

        Mockito.verifyNoMoreInteractions(debugService);
    }

    @Test
    void testGetLostDatabases() {
        DatabaseResponseV3ListCP database = new DatabaseResponseV3ListCP();
        database.setName("test-name");
        when(debugService.findLostDatabases()).thenReturn(singletonList(
                new LostDatabasesResponse("test-id", singletonList(database), null)
        ));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get(FIND_LOST_DB_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].physicalDatabaseId", is("test-id"))
                .body("[0].databases[0].name", is(database.getName()));
    }

    @Test
    void testGetGhostDatabases() {
        final GhostDatabasesResponse ghostDatabasesResponse = new GhostDatabasesResponse("test-id", List.of("test-name"), null);

        when(debugService.findGhostDatabases()).thenReturn(singletonList(ghostDatabasesResponse));
        PhysicalDatabase physicalDatabase = new PhysicalDatabase();
        physicalDatabase.setId(PHYSICAL_DATABASE_ID);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get(FIND_GHOST_DB_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("[0].dbNames[0]", is("test-name"));
    }

    @Test
    void testGetOverallStatus() {
        OverallStatusResponse overallStatusResponse = new OverallStatusResponse("UP", 10, Collections.emptyList());
        when(debugService.getOverallStatus()).thenReturn(overallStatusResponse);
        given().auth().preemptive().basic("cluster-dba", "someDefaultPassword")
                .when().get(GET_OVERALL_STATUS_PATH)
                .then()
                .statusCode(OK.getStatusCode())
                .body("overallHealthStatus", is(overallStatusResponse.getOverallHealthStatus()))
                .body("overallLogicalDbNumber", is(overallStatusResponse.getOverallLogicalDbNumber()));
    }

    protected DumpResponseV3 createEmptyDumpResponse() {
        return new DumpResponseV3(
            new DumpRulesV3(List.of(), List.of(), List.of(), List.of()),
            List.of(), List.of(), List.of(), List.of()
        );
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

    protected DebugLogicalDatabaseV3 createEmptyDebugLogicalDatabase() {
        var declarationConfig = new DebugDatabaseDeclarativeConfigV3();
        var logicalDatabase = new DebugLogicalDatabaseV3();

        logicalDatabase.setDeclaration(declarationConfig);

        return logicalDatabase;
    }
}
