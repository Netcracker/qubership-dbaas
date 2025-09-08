package com.netcracker.cloud.dbaas.controller.error;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import com.netcracker.cloud.dbaas.exceptions.ForbiddenException;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Context
    UriInfo uriInfo;

    @Override
	public Response toResponse(ForbiddenException e) {
        return buildDefaultResponse(uriInfo, e, Response.Status.FORBIDDEN);
    }
}
