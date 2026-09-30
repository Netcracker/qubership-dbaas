package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.dto.v3.ApiVersion;
import com.netcracker.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import com.netcracker.cloud.dbaas.rest.SecureDbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClient;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientLoggingFilter;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.security.filters.DynamicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesServiceAccountToken;
import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.ws.rs.Priorities;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DbaasAdapterRESTClientFactory {
    @Inject
    M2MAuthMode m2mAuthMode;

    @Inject
    TimeMeasurementManager timeMeasurementManager;

    public DbaasAdapter createDbaasAdapterClient(String username, String password, String adapterAddress, String type,
                                                 String identifier, AdapterActionTrackerClient tracker) {
        BasicAuthFilter authFilter = new BasicAuthFilter(username, password);
        DbaasAdapterRestClient restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .register(authFilter)
                .connectTimeout(3, TimeUnit.MINUTES)
                .readTimeout(3, TimeUnit.MINUTES)
                .build(DbaasAdapterRestClient.class);
        return (DbaasAdapter) Proxy.newProxyInstance(DbaasAdapter.class.getClassLoader(), new Class[]{DbaasAdapter.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new DbaasAdapterRESTClient(adapterAddress, type, restClient, identifier, tracker)));
    }

    public DbaasAdapter createDbaasAdapterClientV2(String username, String password, String adapterAddress, String type,
                                                   String identifier, AdapterActionTrackerClient tracker, ApiVersion apiVersions) {
        BasicAuthFilter basicAuthFilter = new BasicAuthFilter(username, password);
        boolean useKubernetesToken = switch (m2mAuthMode) {
            case LEGACY -> false;
            case HYBRID, K8S -> true;
        };
        KubernetesTokenAuthFilter kubernetesTokenAuthFilter = null;
        if (useKubernetesToken) {
            kubernetesTokenAuthFilter = new KubernetesTokenAuthFilter(KubernetesServiceAccountToken::getToken);
        }
        DynamicAuthFilter dynamicAuthFilter = new DynamicAuthFilter(kubernetesTokenAuthFilter != null ? kubernetesTokenAuthFilter : basicAuthFilter);

        DbaasAdapterRestClientV2 restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .register(dynamicAuthFilter, Priorities.AUTHENTICATION)
                .register(new DbaasAdapterRestClientLoggingFilter())
                .connectTimeout(3, TimeUnit.MINUTES)
                .readTimeout(3, TimeUnit.MINUTES)
                .build(DbaasAdapterRestClientV2.class);

        SecureDbaasAdapterRestClientV2 secureRestClient = new SecureDbaasAdapterRestClientV2(restClient, basicAuthFilter, kubernetesTokenAuthFilter, dynamicAuthFilter, useKubernetesToken);

        return (DbaasAdapter) Proxy.newProxyInstance(DbaasAdapter.class.getClassLoader(), new Class[]{DbaasAdapter.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new DbaasAdapterRESTClientV2(adapterAddress, type, secureRestClient, identifier, tracker, apiVersions)));
    }

}
