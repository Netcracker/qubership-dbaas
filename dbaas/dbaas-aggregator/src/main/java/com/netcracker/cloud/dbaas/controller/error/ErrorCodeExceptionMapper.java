package com.netcracker.cloud.dbaas.controller.error;

import com.netcracker.cloud.core.error.runtime.ErrorCodeException;
import com.netcracker.cloud.dbaas.controller.ConfigControllerV1;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

import static com.netcracker.cloud.dbaas.controller.error.Utils.buildDefaultResponse;
import static com.netcracker.cloud.dbaas.controller.error.Utils.createTmfErrorResponse;
import static com.netcracker.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.VALIDATED;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

@Provider
public class ErrorCodeExceptionMapper implements ExceptionMapper<ErrorCodeException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ErrorCodeException e) {
        if (uriInfo.getMatchedResources().stream().map(r -> r.getClass()).anyMatch(c -> c.equals(ConfigControllerV1.class))) {
            return createTmfErrorResponse(uriInfo, e, INTERNAL_SERVER_ERROR, Map.of("type", VALIDATED));
        } else {
            return buildDefaultResponse(uriInfo, e, INTERNAL_SERVER_ERROR);
        }
    }
}
