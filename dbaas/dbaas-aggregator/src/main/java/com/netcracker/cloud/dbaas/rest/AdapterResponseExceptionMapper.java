package com.netcracker.cloud.dbaas.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

public class AdapterResponseExceptionMapper implements ResponseExceptionMapper<AdapterException> {
    private final static ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AdapterException toThrowable(Response response) {
        String responseBody = response.readEntity(String.class);
        String errorMessage;
        try {
            errorMessage = objectMapper.readTree(responseBody).get("message").asText();
        } catch (JsonProcessingException e) {
            errorMessage = responseBody;
        }
        return new AdapterException(response.getStatus(), errorMessage);
    }
}
