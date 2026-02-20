package com.netcracker.cloud.dbaas.security.filters;

import jakarta.annotation.Priority;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

import java.io.IOException;
import java.util.Base64;

import static jakarta.ws.rs.Priorities.AUTHENTICATION;

@Priority(AUTHENTICATION)
public class BasicAuthFilter implements ClientRequestFilter {

    private final String header;

    public BasicAuthFilter(String username, String password) {
        header = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
    }

    @Override
    public void filter(ClientRequestContext clientRequestContext) throws IOException {
        clientRequestContext.getHeaders().add(HttpHeaders.AUTHORIZATION, header);
    }
}
