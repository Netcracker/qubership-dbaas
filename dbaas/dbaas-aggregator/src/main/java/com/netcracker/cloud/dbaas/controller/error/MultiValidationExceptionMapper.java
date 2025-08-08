package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.exceptions.MultiValidationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.CustomLog;

import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.controller.error.Utils.buildResponse;
import static org.qubership.cloud.dbaas.controller.error.Utils.tmfErrorBuilder;
import static org.qubership.cloud.dbaas.controller.error.Utils.tmfResponseBuilder;

@CustomLog
@Provider
public class MultiValidationExceptionMapper implements ExceptionMapper<MultiValidationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(MultiValidationException e) {
        log.warn("{} happened during request to {}. Validation errors: {}", e.getClass().getSimpleName(), uriInfo.getPath(),
                e.getValidationExceptions().stream().map(ErrorCodeException::getMessage).collect(Collectors.joining("\n")));
        Response.Status status = Response.Status.BAD_REQUEST;
        return buildResponse(status,
                () -> tmfResponseBuilder(e, status)
                        .errors(e.getValidationExceptions().stream().map(ve ->
                                tmfErrorBuilder(ve, status).build()).collect(Collectors.toList()))
                        .build());

    }
}
