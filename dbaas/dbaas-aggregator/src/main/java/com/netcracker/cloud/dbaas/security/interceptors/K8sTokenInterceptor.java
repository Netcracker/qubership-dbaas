package com.netcracker.cloud.dbaas.security.interceptors;

import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import com.netcracker.cloud.dbaas.security.K8sTokenWatcher;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class K8sTokenInterceptor implements Interceptor {
    private final AtomicReference<String> token = new AtomicReference<>();
    private final K8sTokenWatcher watcher;
    private final Thread watcherThread;

    public K8sTokenInterceptor(String tokenDir) {
        token.set("");
        watcher = new K8sTokenWatcher(tokenDir, token);
        watcherThread = Thread.startVirtualThread(watcher);
    }

    public @NotNull Response intercept(Interceptor.Chain chain) throws IOException {
        Request requestWithUserAgent = chain.request()
                .newBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
                .build();

        return chain.proceed(requestWithUserAgent);
    }

    public String getTokenIssuer() {
        return watcher.getTokenIssuer();
    }

    public void close() {
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }
}
