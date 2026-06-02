package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.entity.dto.RotationEventPayload;
import com.netcracker.cloud.dbaas.security.filters.AuthFilterSetter;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
public class SecureOperatorWebhookRestClient implements OperatorWebhookRestClient {
    private static final long K8S_AUTH_RETRY_INTERVAL_MINUTES = 60;
    private final boolean isJwtEnabled;

    private final KubernetesTokenAuthFilter kubernetesTokenAuthFilter;

    private final OperatorWebhookRestClient restClient;
    private final AuthFilterSetter authFilterSetter;

    private final AtomicReference<Instant> lastTokenAuthSetTime;

    public SecureOperatorWebhookRestClient(OperatorWebhookRestClient restClient, KubernetesTokenAuthFilter kubernetesTokenAuthFilter, AuthFilterSetter authFilterSetter, boolean isJwtEnabled) {
        this.restClient = restClient;
        this.kubernetesTokenAuthFilter = kubernetesTokenAuthFilter;
        this.authFilterSetter = authFilterSetter;
        this.lastTokenAuthSetTime = new AtomicReference<>(Instant.now());
        this.isJwtEnabled = isJwtEnabled;
    }

    private <R> R executeRequest(final Supplier<R> supplier) {
        try {
            if (isJwtEnabled && authFilterSetter.getAuthFilter() instanceof BasicAuthFilter && Duration.between(lastTokenAuthSetTime.get(), Instant.now()).toMinutes() >= K8S_AUTH_RETRY_INTERVAL_MINUTES) {
                authFilterSetter.setAuthFilter(kubernetesTokenAuthFilter);
                lastTokenAuthSetTime.set(Instant.now());
            }
            return supplier.get();
        } catch (WebApplicationException e) {
            log.error("Request with M2M authentication failed with 'UNAUTHORIZED'");
            throw e;
        }
    }


    @Override
    public Response notify(RotationEventPayload payload) {
        return executeRequest(() -> restClient.notify(payload));
    }

    @Override
    public void close() throws Exception {
        restClient.close();
    }
}
