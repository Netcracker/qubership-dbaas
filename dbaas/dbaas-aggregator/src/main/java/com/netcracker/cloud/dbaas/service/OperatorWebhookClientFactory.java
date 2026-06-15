package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.configProperty.RotationNotificationProperty;
import com.netcracker.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientLoggingFilter;
import com.netcracker.cloud.dbaas.rest.OperatorWebhookRestClient;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesAudienceToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@Slf4j
@ApplicationScoped
public class OperatorWebhookClientFactory {
    @Inject
    TimeMeasurementManager timeMeasurementManager;
    @Inject
    RotationNotificationProperty rotationNotificationProperty;

    @Produces
    @ApplicationScoped
    public OperatorWebhook createOperatorWebhook() {
        KubernetesTokenAuthFilter kubernetesTokenAuthFilter
                = new KubernetesTokenAuthFilter(() -> KubernetesAudienceToken.getToken(rotationNotificationProperty.jwtAudience()));

        OperatorWebhookRestClient restClient = RestClientBuilder.newBuilder().baseUri(URI.create(rotationNotificationProperty.callbackUrl()))
                .register(kubernetesTokenAuthFilter)
                .register(new DbaasAdapterRestClientLoggingFilter())
                .connectTimeout(rotationNotificationProperty.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(rotationNotificationProperty.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .build(OperatorWebhookRestClient.class);

        return (OperatorWebhook) Proxy.newProxyInstance(OperatorWebhook.class.getClassLoader(), new Class[]{OperatorWebhook.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new OperatorWebhookRESTClient(restClient)));
    }
}
