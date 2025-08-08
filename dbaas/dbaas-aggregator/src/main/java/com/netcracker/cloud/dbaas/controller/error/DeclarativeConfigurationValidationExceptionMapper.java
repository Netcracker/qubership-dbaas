package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.dbaas.exceptions.DeclarativeConfigurationValidationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

import static org.qubership.cloud.dbaas.controller.error.Utils.createTmfErrorResponse;
import static org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.VALIDATED;

@Provider
public class DeclarativeConfigurationValidationExceptionMapper implements ExceptionMapper<DeclarativeConfigurationValidationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(DeclarativeConfigurationValidationException e) {
        return createTmfErrorResponse(uriInfo, e, Response.Status.BAD_REQUEST, Map.of("type", VALIDATED));
    }
}
