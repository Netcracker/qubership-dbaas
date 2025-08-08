package org.qubership.cloud.dbaas.security.filters;

import io.quarkus.runtime.Shutdown;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.dbaas.security.K8sTokenWatcher;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.ws.rs.Priorities.AUTHENTICATION;

@Priority(AUTHENTICATION)
@Slf4j
public class K8sTokenAuthFilter implements ClientRequestFilter {
    private final AtomicReference<String> token = new AtomicReference<>();
    private final Thread watcherThread;
    @Inject
    @ConfigProperty(name = "dbaas.security.token.netcracker.path")
    private String tokenLocation;
    @Inject
    @ConfigProperty(name = "dbaas.security.token.netcracker.dir")
    private String tokenDir;

    public K8sTokenAuthFilter() {
        token.set("");

        watcherThread = Thread.startVirtualThread(new K8sTokenWatcher(tokenDir, tokenLocation, token));
    }

    @Override
    public void filter(ClientRequestContext clientRequestContext) throws IOException {
        clientRequestContext.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer " + token.get());
    }

    @Shutdown
    void shutdown() {
        watcherThread.interrupt();
    }
}
