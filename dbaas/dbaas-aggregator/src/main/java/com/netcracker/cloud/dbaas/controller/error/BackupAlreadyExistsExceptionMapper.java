package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.BackupAlreadyExistsException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class BackupAlreadyExistsExceptionMapper implements ExceptionMapper<BackupAlreadyExistsException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(BackupAlreadyExistsException exception) {
        return buildDefaultResponse(uriInfo, exception, Response.Status.CONFLICT);
    }
}
