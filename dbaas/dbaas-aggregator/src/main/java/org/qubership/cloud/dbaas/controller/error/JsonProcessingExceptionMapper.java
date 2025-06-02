package org.qubership.cloud.dbaas.controller.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import org.jboss.resteasy.plugins.providers.jackson._private.JacksonLogger;

import static org.qubership.cloud.dbaas.controller.error.Utils.WARNING_MESSAGE;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

@Slf4j
@Provider
@Priority(1)
public class JsonProcessingExceptionMapper implements ExceptionMapper<JsonProcessingException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(JsonProcessingException e) {
        log.error(WARNING_MESSAGE, e.getClass().getSimpleName(), uriInfo.getPath(), e.getMessage(), e);
        return Response.status(BAD_REQUEST).entity(JacksonLogger.LOGGER.cannotDeserialize()).build();
    }
}
