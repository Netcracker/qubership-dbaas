package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.config.security.M2MAuthModeProducer;
import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import com.netcracker.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import com.netcracker.cloud.dbaas.rest.*;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.DynamicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesServiceAccountToken;
import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DbaasAdapterRESTClientFactory {
    @Inject
    M2MAuthMode m2mAuthMode;

    @ConfigProperty(name = "dbaas.adapter.client.timeout-seconds", defaultValue = "180")
    long adapterTimeoutSeconds;

    @Inject
    TimeMeasurementManager timeMeasurementManager;

    public DbaasAdapter createDbaasAdapterClient(String username, String password, String adapterAddress, String type,
                                                 String identifier, AdapterActionTrackerClient tracker) {
        BasicAuthFilter authFilter = new BasicAuthFilter(username, password);
        DbaasAdapterRestClient restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .register(authFilter)
                .register(new AdapterResponseExceptionMapper())
                .connectTimeout(adapterTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(adapterTimeoutSeconds, TimeUnit.SECONDS)
                .build(DbaasAdapterRestClient.class);
        return (DbaasAdapter) Proxy.newProxyInstance(DbaasAdapter.class.getClassLoader(), new Class[]{DbaasAdapter.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new DbaasAdapterRESTClient(adapterAddress, type, restClient, identifier, tracker)));
    }

    public DbaasAdapter createDbaasAdapterClientV2(String username, String password, String adapterAddress, String type,
                                                   String identifier, AdapterActionTrackerClient tracker, ApiVersion apiVersions) {
        BasicAuthFilter basicAuthFilter = new BasicAuthFilter(username, password);
        boolean m2mEnabled = M2MAuthModeProducer.isKubernetesTokenEnabled(m2mAuthMode);
        KubernetesTokenAuthFilter kubernetesTokenAuthFilter = null;
        if (m2mEnabled) {
            kubernetesTokenAuthFilter = new KubernetesTokenAuthFilter(KubernetesServiceAccountToken::getToken);
        }
        DynamicAuthFilter dynamicAuthFilter = new DynamicAuthFilter(kubernetesTokenAuthFilter != null ? kubernetesTokenAuthFilter : basicAuthFilter);

        DbaasAdapterRestClientV2 restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .register(dynamicAuthFilter, Priorities.AUTHENTICATION)
                .register(new DbaasAdapterRestClientLoggingFilter())
                .register(new AdapterResponseExceptionMapper())
                .connectTimeout(adapterTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(adapterTimeoutSeconds, TimeUnit.SECONDS)
                .build(DbaasAdapterRestClientV2.class);

        SecureDbaasAdapterRestClientV2 secureRestClient = new SecureDbaasAdapterRestClientV2(restClient, basicAuthFilter, kubernetesTokenAuthFilter, dynamicAuthFilter, m2mEnabled);

        return (DbaasAdapter) Proxy.newProxyInstance(DbaasAdapter.class.getClassLoader(), new Class[]{DbaasAdapter.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new DbaasAdapterRESTClientV2(adapterAddress, type, secureRestClient, identifier, tracker, apiVersions)));
    }

}
