package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.exceptions.AdapterException;
import com.netcracker.cloud.dbaas.monitoring.AdapterHealthStatus;
import com.netcracker.cloud.dbaas.security.filters.AuthFilterSetter;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static jakarta.ws.rs.core.Response.Status.FORBIDDEN;
import static jakarta.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecureDbaasAdapterRestClientV2Test {

    @Mock
    private DbaasAdapterRestClientV2 restClient;

    @Mock
    private BasicAuthFilter basicAuthFilter;

    @Mock
    private KubernetesTokenAuthFilter kubernetesTokenAuthFilter;

    @Mock
    private AuthFilterSetter authFilterSetter;

    @Test
    void shouldExecuteRequestWithBasicAuthWhenJwtDisabled() {
        SecureDbaasAdapterRestClientV2 secureClient = new SecureDbaasAdapterRestClientV2(
                restClient, basicAuthFilter, kubernetesTokenAuthFilter, authFilterSetter, false);
        AdapterHealthStatus healthStatus = new AdapterHealthStatus("ok");
        when(restClient.getHealth()).thenReturn(healthStatus);

        AdapterHealthStatus result = secureClient.getHealth();

        assertSame(healthStatus, result);
        verify(restClient).getHealth();
        verify(authFilterSetter, never()).setAuthFilter(any());
    }

    @Test
    void shouldSwitchToBasicAuthOn401WhenUsingTokenAuth() {
        when(authFilterSetter.getAuthFilter()).thenReturn(kubernetesTokenAuthFilter);
        SecureDbaasAdapterRestClientV2 secureClient = new SecureDbaasAdapterRestClientV2(
                restClient, basicAuthFilter, kubernetesTokenAuthFilter, authFilterSetter, true);

        AdapterException unauthorizedException = new AdapterException(UNAUTHORIZED.getStatusCode(), UNAUTHORIZED.getReasonPhrase());

        AdapterHealthStatus healthStatus = new AdapterHealthStatus("ok");
        when(restClient.getHealth())
                .thenThrow(unauthorizedException)
                .thenReturn(healthStatus);

        AdapterHealthStatus result = secureClient.getHealth();

        assertSame(healthStatus, result);
        verify(authFilterSetter).setAuthFilter(basicAuthFilter);
        verify(restClient, times(2)).getHealth();
    }

    @Test
    void shouldRethrowExceptionWhenNotUnauthorizedOrNotTokenAuth() {
        when(authFilterSetter.getAuthFilter()).thenReturn(basicAuthFilter);
        SecureDbaasAdapterRestClientV2 secureClient = new SecureDbaasAdapterRestClientV2(
                restClient, basicAuthFilter, kubernetesTokenAuthFilter, authFilterSetter, true);

        AdapterException forbiddenException = new AdapterException(FORBIDDEN.getStatusCode(), FORBIDDEN.getReasonPhrase());
        when(restClient.getHealth()).thenThrow(forbiddenException);

        assertThrows(AdapterException.class, secureClient::getHealth);
        verify(authFilterSetter, never()).setAuthFilter(any());
        verify(restClient, times(1)).getHealth();
    }

    @Test
    void shouldDelegateAllMethodsToRestClient() throws Exception {
        SecureDbaasAdapterRestClientV2 secureClient = new SecureDbaasAdapterRestClientV2(
                restClient, basicAuthFilter, kubernetesTokenAuthFilter, authFilterSetter, false);

        secureClient.handshake("postgres");
        verify(restClient).handshake("postgres");

        secureClient.supports("postgres");
        verify(restClient).supports("postgres");

        secureClient.getDatabases("postgres");
        verify(restClient).getDatabases("postgres");

        secureClient.close();
        verify(restClient).close();
    }
}
