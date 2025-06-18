package org.qubership.cloud.dbaas.controller;

import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(ApiVersionController.class)
class ApiVersionControllerTest {

    @Test
    void testApiVersionController() {
        given().when().get()
                .then()
                .statusCode(OK.getStatusCode())
                .body(containsString("major"))
                .body(containsString("minor"))
                .body(containsString("supportedMajors"));
    }
}
