package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.InvalidUpdateConnectionPropertiesRequestException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class InvalidUpdateConnectionPropertiesRequestExceptionMapper implements ExceptionMapper<InvalidUpdateConnectionPropertiesRequestException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(InvalidUpdateConnectionPropertiesRequestException e) {
        return buildDefaultResponse(uriInfo, e, Response.Status.BAD_REQUEST);
    }
}
