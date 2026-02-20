package com.netcracker.cloud.dbaas.rest;

import com.netcracker.cloud.dbaas.monitoring.AdapterHealthStatus;
import com.netcracker.cloud.dbaas.security.filters.AuthFilterSetter;
import com.netcracker.cloud.dbaas.security.filters.BasicAuthFilter;
import com.netcracker.cloud.dbaas.security.filters.KubernetesTokenAuthFilter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        Response unauthorizedResponse = mock(Response.class);
        when(unauthorizedResponse.getStatus()).thenReturn(401);
        when(unauthorizedResponse.getStatusInfo()).thenReturn(getStatusType(401));
        WebApplicationException unauthorizedException = new WebApplicationException(unauthorizedResponse);

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

        Response forbiddenResponse = mock(Response.class);
        when(forbiddenResponse.getStatus()).thenReturn(403);
        when(forbiddenResponse.getStatusInfo()).thenReturn(getStatusType(403));
        WebApplicationException forbiddenException = new WebApplicationException(forbiddenResponse);
        when(restClient.getHealth()).thenThrow(forbiddenException);

        assertThrows(WebApplicationException.class, secureClient::getHealth);
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

    private Response.StatusType getStatusType(int code) {
        return new Response.StatusType() {
            @Override
            public int getStatusCode() {
                return 403;
            }

            @Override
            public Response.Status.Family getFamily() {
                return null;
            }

            @Override
            public String getReasonPhrase() {
                return "";
            }
        };
    }
}
