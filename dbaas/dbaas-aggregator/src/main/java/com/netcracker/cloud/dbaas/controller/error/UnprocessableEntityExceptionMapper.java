package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.UnprocessableEntityException;
import com.netcracker.cloud.dbaas.utils.CustomExceptionStatus;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class UnprocessableEntityExceptionMapper implements ExceptionMapper<UnprocessableEntityException> {
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(UnprocessableEntityException e) {
        return buildDefaultResponse(uriInfo, e, CustomExceptionStatus.UNPROCESSABLE_ENTITY);
    }
}
