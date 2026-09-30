package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.monitoring.interceptor.TimeMeasurementManager;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClientV2;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.DynamicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DbaasAdapterRESTClientFactoryTest {
    @Mock
    TimeMeasurementManager timeMeasurementManager;

    @InjectMocks
    DbaasAdapterRESTClientFactory factory;

    @Test
    void clientV2StartsWithBasicAuthWhenKubernetesM2MIsDisabled() {
        factory.m2mEnabled = false;

        assertInstanceOf(BasicAuthFilter.class, initialAuthFilterOfClientV2());
    }

    @Test
    void clientV2StartsWithKubernetesTokenWhenKubernetesM2MIsEnabled() {
        factory.m2mEnabled = true;

        assertInstanceOf(KubernetesTokenAuthFilter.class, initialAuthFilterOfClientV2());
    }

    private ClientRequestFilter initialAuthFilterOfClientV2() {
        RestClientBuilder builder = mock(RestClientBuilder.class, RETURNS_SELF);
        when(builder.build(DbaasAdapterRestClientV2.class)).thenReturn(mock(DbaasAdapterRestClientV2.class));
        when(timeMeasurementManager.provideTimeMeasurementInvocationHandler(any())).thenReturn((proxy, method, args) -> null);
        try (MockedStatic<RestClientBuilder> restClientBuilder = mockStatic(RestClientBuilder.class)) {
            restClientBuilder.when(RestClientBuilder::newBuilder).thenReturn(builder);
            factory.createDbaasAdapterClientV2("user", "password", "http://adapter:8080", "postgresql",
                    "adapter-id", mock(AdapterActionTrackerClient.class), null);
        }
        ArgumentCaptor<Object> components = ArgumentCaptor.forClass(Object.class);
        verify(builder, atLeastOnce()).register(components.capture(), anyInt());
        return components.getAllValues().stream()
                .filter(DynamicAuthFilter.class::isInstance)
                .map(DynamicAuthFilter.class::cast)
                .findFirst()
                .orElseThrow()
                .getAuthFilter();
    }
}
