package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.v3.ApiVersion;
import org.qubership.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import org.qubership.cloud.dbaas.rest.BasicAuthFilter;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClient;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientLoggingFilter;
import org.qubership.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DbaasAdapterRESTClientFactory {
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
        BasicAuthFilter authFilter = new BasicAuthFilter(username, password);
        DbaasAdapterRestClientV2 restClient = RestClientBuilder.newBuilder().baseUri(URI.create(adapterAddress))
                .register(authFilter)
                .register(new DbaasAdapterRestClientLoggingFilter())
                .connectTimeout(3, TimeUnit.MINUTES)
                .readTimeout(3, TimeUnit.MINUTES)
                .build(DbaasAdapterRestClientV2.class);
        return (DbaasAdapter) Proxy.newProxyInstance(DbaasAdapter.class.getClassLoader(), new Class[]{DbaasAdapter.class},
                timeMeasurementManager.provideTimeMeasurementInvocationHandler(new DbaasAdapterRESTClientV2(adapterAddress, type, restClient, identifier, tracker, apiVersions)));
    }

}
