package org.qubership.cloud.dbaas.controller.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.cloud.dbaas.exceptions.NamespaceBackupDeletionFailedException;
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
public class NamespaceBackupDeletionFailedExceptionMapper implements ExceptionMapper<NamespaceBackupDeletionFailedException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(NamespaceBackupDeletionFailedException e) {
        String requestURI = uriInfo.getPath();
        String resultAsJson;
        try {
            resultAsJson = new ObjectMapper().writeValueAsString(e.getBackupToDelete());
        } catch (JsonProcessingException jpe) {
            resultAsJson = "Failed to convert result to JSON format. Cause: " + jpe.getMessage();
        }
        log.warn(WARNING_MESSAGE, e.getClass().getSimpleName(), requestURI, e.getMessage() +
                "\nresult: " + resultAsJson);
        Response.Status status = Response.Status.INTERNAL_SERVER_ERROR;
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("result", e.getBackupToDelete());
        return buildResponse(status,
                () -> tmfResponseBuilder(e, status)
                        .meta(responseMap)
                        .build());

    }
}
