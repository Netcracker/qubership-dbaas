package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.core.error.runtime.ErrorCode;
import com.netcracker.cloud.dbaas.exceptions.ErrorCodes;
import com.netcracker.cloud.dbaas.exceptions.ValidationException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.stream.Collectors;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class ConstraintViolationExceptionMapper
        implements ExceptionMapper<ConstraintViolationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        String detail = exception.getConstraintViolations().stream()
                .map(v -> {
                    String fieldName = v.getPropertyPath().toString();
                    int dotIndex = fieldName.lastIndexOf('.');
                    if (dotIndex != -1) {
                        fieldName = fieldName.substring(dotIndex + 1);
                    }
                    return String.format("%s: %s", fieldName, v.getMessage());
                })
                .collect(Collectors.joining("; "));

        ErrorCode errorCode = ErrorCodes.CORE_DBAAS_4043;

        ValidationException wrapped = new ValidationException(errorCode, detail, null) {
        };
        return buildDefaultResponse(uriInfo, wrapped, Response.Status.BAD_REQUEST);
    }
}
