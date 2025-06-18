package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.dbaas.exceptions.DbNotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static org.qubership.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class DbNotFoundExceptionMapper implements ExceptionMapper<DbNotFoundException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(DbNotFoundException e) {
        return buildDefaultResponse(uriInfo, e, Response.Status.NOT_FOUND);
    }
}
