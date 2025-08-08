package org.qubership.cloud.dbaas.controller;

import org.qubership.cloud.dbaas.monitoring.indicators.AggregatedHealthResponse;
import org.qubership.cloud.dbaas.service.HealthService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;

import static org.qubership.cloud.dbaas.monitoring.indicators.HealthStatus.PROBLEM;
import static org.qubership.cloud.dbaas.monitoring.indicators.HealthStatus.UP;

@Path("/")
@PermitAll
public class HealthController {

    @Inject
    HealthService healthService;

    @Operation(hidden = true)
    @Path("/probes/live")
    @GET
    public Response livenessProbe() {
        return getProbesCheckStatus();
    }

    @Operation(hidden = true)
    @Path("/probes/ready")
    @GET
    public Response readinessProbe() {
        return getProbesCheckStatus();
    }

    @Operation(hidden = true)
    @Path("/health")
    @GET
    public Response healthProbe() {
        return getHealthCheckStatus();
    }

    private Response getProbesCheckStatus() {
        AggregatedHealthResponse probesResponse = healthService.getProbes();
        if (UP == probesResponse.getStatus()) {
            return Response.ok("Successfully checked dbaas-aggregator's health checks").build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(probesResponse.getComponents()).build();
        }
    }

    private Response getHealthCheckStatus() {
        AggregatedHealthResponse healthCheckResponse = healthService.getHealth();
        if (UP == healthCheckResponse.getStatus() || PROBLEM == healthCheckResponse.getStatus()) {
            return Response.ok(healthCheckResponse).build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(healthCheckResponse).build();
        }
    }
}
