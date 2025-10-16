package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.IntegrityViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class IntegrityViolationExceptionMapper implements ExceptionMapper<IntegrityViolationException> {
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(IntegrityViolationException exception) {
        return buildDefaultResponse(uriInfo, exception, Response.Status.CONFLICT);
    }
}
