package com.netcracker.cloud.dbaas.security.filters;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import jakarta.ws.rs.client.ClientRequestFilter;

public interface AuthFilterSetter {
    void setAuthFilter(ClientRequestFilter authFilter);

    ClientRequestFilter getAuthFilter();
}
