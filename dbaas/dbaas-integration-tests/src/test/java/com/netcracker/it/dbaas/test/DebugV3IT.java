package com.netcracker.it.dbaas.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.it.dbaas.helpers.BGHelper;
import com.netcracker.it.dbaas.helpers.BalancingRulesHelperV3;
import com.netcracker.it.dbaas.helpers.DbaasDebugHelperV3;
import com.netcracker.it.dbaas.helpers.DeclarativeConfigHelper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.stream.Collectors;

@Slf4j
public class DebugV3IT extends AbstractIT {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static DbaasDebugHelperV3 debugHelper;

    @BeforeAll
    public static void setUp() throws IOException, InterruptedException {
        debugHelper = new DbaasDebugHelperV3(
                helperV3,
                new DeclarativeConfigHelper(helperV3),
                new BGHelper(helperV3),
                new BalancingRulesHelperV3(helperV3)
        );

        cleanUp();

        debugHelper.generateTestData();
    }

    @AfterAll
    static void cleanUp() throws IOException {
        log.info("Clean test data");

        debugHelper.deleteTestData();
    }

    @Test
    void testGetDumpOfDbaasDatabaseInformationAsJson() throws IOException {

        try (var response = executeGetDumpOfDbaasDatabaseInformationRequest("application/json")) {
            Assertions.assertDoesNotThrow(
                    () -> objectMapper.readTree(response.body().bytes()),
                    "Returned dump must be valid Json"
            );
        }
    }

    @Test
    void testGetDumpOfDbaasDatabaseInformationAsZipFile() throws IOException {

        try (var response = executeGetDumpOfDbaasDatabaseInformationRequest("application/octet-stream")) {
            var zipBytes = response.body().bytes();

            Assertions.assertTrue(zipBytes.length > 0, "Returned zip file must not be empty");
        }
    }

    @Test
    void findLostDatabases() {
        Assertions.assertDoesNotThrow(() -> helperV3.findLostDatabases());
    }

    @Test
    void findGhostDatabases() {
        Assertions.assertDoesNotThrow(() -> helperV3.findGhostDatabases());
    }

    @Test
    void getOverallStatus() {
        Assertions.assertDoesNotThrow(() -> helperV3.getOverallStatus());
    }

    @Test
    void testFindDebugLogicalDatabasesWithNullFilterRsqlQuery() throws IOException {
        doTestFindDebugLogicalDatabasesWithFilterRsqlQuery(null);
    }

    @Test
    void testFindDebugLogicalDatabasesWithFilledFilterRsqlQuery() throws IOException {
        var filterRsqlQuery = """
                namespace==dbaas-autotests;microservice==dbaas-declarative-service;
                logicalDbName==configs;type!=clickhouse;roles=in=("ro","rw","admin")
                """.lines().collect(Collectors.joining());

        doTestFindDebugLogicalDatabasesWithFilterRsqlQuery(filterRsqlQuery);
    }

    protected void doTestFindDebugLogicalDatabasesWithFilterRsqlQuery(String filterRsqlQuery) throws IOException {

        try (var response = executeFindDebugLogicalDatabasesWithFilterRsqlQuery(filterRsqlQuery)) {
            var responseBody = response.body().bytes();

            Assertions.assertDoesNotThrow(
                    () -> {
                        var arrayNode = objectMapper.readTree(responseBody);

                        Assertions.assertFalse(arrayNode.isEmpty());
                    },
                    "Returned debug logical databases must be valid not empty array of Jsons"
            );
        }
    }

    protected Response executeGetDumpOfDbaasDatabaseInformationRequest(String acceptHeaderValue) throws IOException {
        Request request = helperV3.createGetDumpOfDbaasDatabaseInformationRequest(acceptHeaderValue);
        Response response = helperV3.executeRequest(request, 200);

        var responseBody = response.body();

        Assertions.assertNotNull(responseBody);
        Assertions.assertEquals(responseBody.contentType(), MediaType.parse(acceptHeaderValue));

        return response;
    }

    protected Response executeFindDebugLogicalDatabasesWithFilterRsqlQuery(String filterRsqlQuery) throws IOException {
        Request request = helperV3.createFindDebugLogicalDatabasesRequest(filterRsqlQuery);
        Response response = helperV3.executeRequest(request, 200);

        var responseBody = response.body();

        Assertions.assertNotNull(responseBody);

        return response;
    }
}
