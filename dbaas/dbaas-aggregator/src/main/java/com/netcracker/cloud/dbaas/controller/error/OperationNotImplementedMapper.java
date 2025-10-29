package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.FunctionalityNotImplemented;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;
import static jakarta.ws.rs.core.Response.Status.NOT_IMPLEMENTED;

@Provider
public class OperationNotImplementedMapper implements ExceptionMapper<FunctionalityNotImplemented> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(FunctionalityNotImplemented exception) {
        return buildDefaultResponse(uriInfo, exception, NOT_IMPLEMENTED);
    }
}
