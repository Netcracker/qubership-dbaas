package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.DatabaseBackupNotSupportedException;
import com.netcracker.cloud.dbaas.utils.CustomExceptionStatus;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class DatabaseBackupNotSupportedExceptionMapper implements ExceptionMapper<DatabaseBackupNotSupportedException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(DatabaseBackupNotSupportedException exception) {
        return buildDefaultResponse(uriInfo, exception, CustomExceptionStatus.UNPROCESSABLE_ENTITY);
    }
}
