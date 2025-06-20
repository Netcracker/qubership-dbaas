package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.core.error.runtime.ErrorCodeException;
import org.qubership.cloud.dbaas.controller.ConfigControllerV1;
import org.qubership.cloud.dbaas.exceptions.UnknownErrorCodeException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

import static org.qubership.cloud.dbaas.controller.error.Utils.buildDefaultResponse;
import static org.qubership.cloud.dbaas.controller.error.Utils.createTmfErrorResponse;
import static org.qubership.cloud.dbaas.dto.conigs.DeclarativeResponse.Condition.VALIDATED;
import static org.qubership.cloud.dbaas.exceptions.ErrorCodes.CORE_DBAAS_2000;
import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

@Provider
public class DefaultExceptionMapper implements ExceptionMapper<Exception> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(Exception e) {
        if (uriInfo.getMatchedResources().stream().map(r -> r.getClass()).anyMatch(c -> c.equals(ConfigControllerV1.class))) {
            ErrorCodeException unexpectedException = new ErrorCodeException(CORE_DBAAS_2000, e.getMessage(), e);
            return createTmfErrorResponse(uriInfo, unexpectedException, INTERNAL_SERVER_ERROR, Map.of("type", VALIDATED));
        } else {
            UnknownErrorCodeException wrapper = new UnknownErrorCodeException(e);
            return buildDefaultResponse(uriInfo, wrapper, INTERNAL_SERVER_ERROR);
        }
    }
}
