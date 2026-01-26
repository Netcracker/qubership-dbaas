package com.netcracker.cloud.dbaas.security.filters;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.function.Supplier;

@Slf4j
public class KubernetesTokenAuthFilter implements ClientRequestFilter {
    private final Supplier<String> tokenSupplier;

    public KubernetesTokenAuthFilter(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public void filter(ClientRequestContext clientRequestContext) throws IOException {
        clientRequestContext.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSupplier.get());
    }
}
