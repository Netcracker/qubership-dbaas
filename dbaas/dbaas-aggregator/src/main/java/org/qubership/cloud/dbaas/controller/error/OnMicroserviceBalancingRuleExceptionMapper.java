package org.qubership.cloud.dbaas.controller.error;

import org.qubership.cloud.dbaas.exceptions.OnMicroserviceBalancingRuleException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import static org.qubership.cloud.dbaas.controller.error.Utils.buildDefaultResponse;

@Provider
public class OnMicroserviceBalancingRuleExceptionMapper implements ExceptionMapper<OnMicroserviceBalancingRuleException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(OnMicroserviceBalancingRuleException e) {
        return buildDefaultResponse(uriInfo, e, Response.Status.INTERNAL_SERVER_ERROR);
    }
}
