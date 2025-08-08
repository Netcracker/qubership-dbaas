package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.dbaas.exceptions.TrackingIdNotFoundException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

import static com.netcracker.cloud.dbaas.controller.error.Utils.createTmfErrorResponse;
import static com.netcracker.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.VALIDATED;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;

@Provider
public class TrackingIdNotFoundExceptionMapper implements ExceptionMapper<TrackingIdNotFoundException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(TrackingIdNotFoundException e) {
        return createTmfErrorResponse(uriInfo, e, NOT_FOUND, Map.of("type", VALIDATED));
    }
}
