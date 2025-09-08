package com.netcracker.cloud.dbaas.security.filters;

import jakarta.ws.rs.client.ClientRequestFilter;

public interface AuthFilterSelector {
    void selectAuthFilter(ClientRequestFilter authFilter);

    ClientRequestFilter getAuthFilter();
}
