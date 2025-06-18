package org.qubership.cloud.dbaas.controller;

import org.qubership.cloud.dbaas.integration.config.PostgresqlContainerResource;
import org.qubership.cloud.dbaas.monitoring.indicators.AggregatedHealthResponse;
import org.qubership.cloud.dbaas.monitoring.indicators.HealthStatus;
import org.qubership.cloud.dbaas.service.HealthService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.Response.Status.OK;
import static jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(PostgresqlContainerResource.class)
@TestHTTPEndpoint(HealthController.class)
class HealthControllerTest {

    @InjectMock
    HealthService healthService;

    @Test
    public void testLivenessProbe() {
        when(healthService.getProbes()).thenReturn(new AggregatedHealthResponse(HealthStatus.UP, null));
        given().when().get("/probes/live")
                .then()
                .statusCode(OK.getStatusCode());

        when(healthService.getProbes()).thenReturn(new AggregatedHealthResponse(HealthStatus.DOWN, null));
        given().when().get("/probes/live")
                .then()
                .statusCode(SERVICE_UNAVAILABLE.getStatusCode());
    }

    @Test
    public void testReadinessProbe() {
        when(healthService.getProbes()).thenReturn(new AggregatedHealthResponse(HealthStatus.UP, null));
        given().when().get("/probes/ready")
                .then()
                .statusCode(OK.getStatusCode());

        when(healthService.getProbes()).thenReturn(new AggregatedHealthResponse(HealthStatus.DOWN, null));
        given().when().get("/probes/ready")
                .then()
                .statusCode(SERVICE_UNAVAILABLE.getStatusCode());
    }
}
