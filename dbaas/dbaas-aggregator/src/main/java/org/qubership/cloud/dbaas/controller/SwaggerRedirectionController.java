package org.qubership.cloud.dbaas.controller;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;

import java.net.URI;

@Path("/")
@PermitAll
public class SwaggerRedirectionController {

    @GET
    @Operation(hidden = true)
    public Response getRoot() {
        return Response.status(Response.Status.FOUND).location(URI.create("/swagger-ui")).build();
    }
}
