package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.IllegalResourceStateException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class IllegalResourceStateExceptionMapper implements ExceptionMapper<IllegalResourceStateException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(IllegalResourceStateException exception) {
        return buildDefaultResponse(uriInfo, exception, Response.Status.CONFLICT);
    }
}
