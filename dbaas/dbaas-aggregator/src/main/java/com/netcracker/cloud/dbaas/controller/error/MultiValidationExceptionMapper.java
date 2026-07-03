package com.netcracker.cloud.dbaas.controller.error;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import com.netcracker.cloud.dbaas.exceptions.MultiValidationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.CustomLog;

import java.util.stream.Collectors;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildResponse;
import static com.netcracker.cloud.dbaas.controller.error.Utils.tmfErrorBuilder;
import static com.netcracker.cloud.dbaas.controller.error.Utils.tmfResponseBuilder;

@CustomLog
@Provider
public class MultiValidationExceptionMapper implements ExceptionMapper<MultiValidationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(MultiValidationException e) {
        StructuredLog.warn(log, "happened during request to . Validation errors:", "arg0", e.getClass().getSimpleName(), "arg1", uriInfo.getPath(), "status", e.getValidationExceptions().stream().map(ErrorCodeException::getMessage).collect(Collectors.joining("\n")));
        Response.Status status = Response.Status.BAD_REQUEST;
        return buildResponse(status);

    }
}
