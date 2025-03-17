package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.dbaas.exceptions.AdapterUnavailableException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static org.qubership.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class AdapterUnavailableExceptionMapper implements ExceptionMapper<AdapterUnavailableException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(AdapterUnavailableException e) {
        return buildDefaultResponse(uriInfo, e, Response.Status.BAD_GATEWAY);
    }
}
