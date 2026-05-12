package com.netcracker.cloud.dbaas.security.filters;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KubernetesTokenAuthFilterTest {

    @Mock
    private ClientRequestContext requestContext;

    @Test
    void shouldAddBearerTokenToAuthorizationHeader() throws IOException {
        String token = "test-k8s-token-12345";
        Supplier<String> tokenSupplier = () -> token;
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        when(requestContext.getHeaders()).thenReturn(headers);

        KubernetesTokenAuthFilter filter = new KubernetesTokenAuthFilter(tokenSupplier);

        filter.filter(requestContext);

        verify(requestContext).getHeaders();
        assertTrue(headers.containsKey(HttpHeaders.AUTHORIZATION));
        assertEquals("Bearer " + token, headers.getFirst(HttpHeaders.AUTHORIZATION));
    }
}
