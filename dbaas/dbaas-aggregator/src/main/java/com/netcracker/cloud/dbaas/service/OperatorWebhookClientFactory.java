package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import com.netcracker.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientLoggingFilter;
import com.netcracker.cloud.dbaas.rest.OperatorWebhookRestClient;
import com.netcracker.cloud.dbaas.rest.SecureOperatorWebhookRestClient;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.DynamicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesAudienceToken;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesServiceAccountToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.Produces;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Builds the JAX-RS REST client for the operator rotation webhook.
 * Mirrors the pattern in DbaasAdapterRESTClientFactory: programmatic RestClientBuilder
 * with a ClientRequestFilter that injects a fresh Bearer token on every request.
 *
 * The token is read from a Kubernetes projected volume mounted at tokenPath
 * (audience=dbaas-operator), mirroring the operator Go client's tokensource.GetAudienceToken.
 */
@Slf4j
@ApplicationScoped
public class OperatorWebhookClientFactory {
    @ConfigProperty(name = "dbaas.security.k8s.jwt.enabled")
    boolean jwtEnabled;

    @Inject
    TimeMeasurementManager timeMeasurementManager;

    @Produces
    @ApplicationScoped
    public OperatorWebhook createOperatorWebhook() {
        KubernetesTokenAuthFilter kubernetesTokenAuthFilter = null;
        if (jwtEnabled) {
            kubernetesTokenAuthFilter = new KubernetesTokenAuthFilter(() -> KubernetesAudienceToken.getToken("dbaas-operator"));
        } else {
            log.error("M2M token required");
        }
        DynamicAuthFilter dynamicAuthFilter = new DynamicAuthFilter(kubernetesTokenAuthFilter);

        OperatorWebhookRestClient restClient = RestClientBuilder.newBuilder().baseUri(URI.create("http://dbaas-operator:8080"))
                .register(dynamicAuthFilter, Priorities.AUTHENTICATION)
                .register(new DbaasAdapterRestClientLoggingFilter())
                .connectTimeout(3, TimeUnit.MINUTES)
                .readTimeout(3, TimeUnit.MINUTES)
                .build(OperatorWebhookRestClient.class);

        SecureOperatorWebhookRestClient secureRestClient = new SecureOperatorWebhookRestClient(restClient, kubernetesTokenAuthFilter, dynamicAuthFilter, jwtEnabled);

        return (OperatorWebhook) Proxy.newProxyInstance(OperatorWebhook.class.getClassLoader(), new Class[]{OperatorWebhook.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new OperatorWebhookRESTClient(secureRestClient)));
    }
}
