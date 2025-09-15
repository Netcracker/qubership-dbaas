package com.netcracker.cloud.dbaas.security.filters;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.io.IOException;

public class DynamicAuthFilter implements ClientRequestFilter, AuthFilterSelector {
    private volatile ClientRequestFilter authFilter;

    public DynamicAuthFilter(ClientRequestFilter defaultAuthFilter) {
        this.authFilter = defaultAuthFilter;
    }

    @Override
    public void filter(ClientRequestContext clientRequestContext) throws IOException {
        authFilter.filter(clientRequestContext);
    }

    @Override
    public void selectAuthFilter(ClientRequestFilter authFilter) {
        this.authFilter = authFilter;
    }

    @Override
    public ClientRequestFilter getAuthFilter() {
        return this.authFilter;
    }
}
