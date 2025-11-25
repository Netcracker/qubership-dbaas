package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

public class AdapterResponseExceptionMapper implements ResponseExceptionMapper<AdapterException> {

    @Override
    public AdapterException toThrowable(Response response) {
        String errorMsg = response.readEntity(String.class);
        return new AdapterException(errorMsg);
    }
}
