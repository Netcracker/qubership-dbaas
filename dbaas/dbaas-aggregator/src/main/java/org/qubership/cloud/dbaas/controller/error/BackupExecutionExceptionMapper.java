package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.dbaas.exceptions.BackupExecutionException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.CustomLog;

import static org.qubership.cloud.dbaas.controller.error.Utils.WARNING_MESSAGE;
import static org.qubership.cloud.dbaas.controller.error.Utils.buildResponse;
import static org.qubership.cloud.dbaas.controller.error.Utils.tmfResponseBuilder;

@CustomLog
@Provider
public class BackupExecutionExceptionMapper implements ExceptionMapper<BackupExecutionException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(BackupExecutionException e) {
        String requestURI = uriInfo.getPath();
        log.warn(WARNING_MESSAGE, e.getClass().getSimpleName(), requestURI, e.getMessage());
        Response.Status status = Response.Status.INTERNAL_SERVER_ERROR;
        final Response response = buildResponse(status,
                () -> tmfResponseBuilder(e, status).build(),
                e.getLocation());
        return response;
    }
}
