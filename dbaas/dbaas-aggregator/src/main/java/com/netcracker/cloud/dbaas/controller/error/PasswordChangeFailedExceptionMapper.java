package org.qubership.cloud.dbaas.controller.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.exceptions.PasswordChangeFailedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.CustomLog;

import java.util.HashMap;
import java.util.Map;

import static org.qubership.cloud.dbaas.controller.error.Utils.WARNING_MESSAGE;
import static org.qubership.cloud.dbaas.controller.error.Utils.buildResponse;
import static org.qubership.cloud.dbaas.controller.error.Utils.tmfResponseBuilder;

@CustomLog
@Provider
public class PasswordChangeFailedExceptionMapper implements ExceptionMapper<PasswordChangeFailedException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(PasswordChangeFailedException e) {
        String requestURI = uriInfo.getPath();
        String responseAsJson;
        try {
            responseAsJson = new ObjectMapper().writeValueAsString(e.getResponse());
        } catch (JsonProcessingException jpe) {
            responseAsJson = "Failed to convert response to JSON format. Cause: " + jpe.getMessage();
        }
        log.warn(WARNING_MESSAGE, e.getClass().getSimpleName(), requestURI, e.getMessage() +
                "\nresponse: " + responseAsJson);
        Response.Status status = Response.Status.fromStatusCode(e.getStatus());
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("response", e.getResponse());
        return buildResponse(status,
                () -> tmfResponseBuilder(e, status)
                        .meta(responseMap)
                        .build());
    }
}
