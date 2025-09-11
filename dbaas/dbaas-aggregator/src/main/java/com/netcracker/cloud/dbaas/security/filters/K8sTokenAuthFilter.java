package com.netcracker.cloud.dbaas.security.filters;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.netcracker.cloud.dbaas.security.K8sTokenWatcher;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@NoArgsConstructor
public class K8sTokenAuthFilter implements ClientRequestFilter {
    private final AtomicReference<String> token = new AtomicReference<>();
    private Thread watcherThread;

    public K8sTokenAuthFilter(String tokenDir) {
        token.set("");

        watcherThread = Thread.startVirtualThread(new K8sTokenWatcher(tokenDir, token));
    }

    @Override
    public void filter(ClientRequestContext clientRequestContext) throws IOException {
        clientRequestContext.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer " + token.get());
    }

    public void close() {
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }
}
