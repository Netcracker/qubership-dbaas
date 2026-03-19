package com.netcracker.cloud.dbaas.security.filters;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicAuthFilterTest {

    @Mock
    private ClientRequestFilter defaultFilter;

    @Mock
    private ClientRequestFilter newFilter;

    @Mock
    private ClientRequestContext requestContext;

    @Test
    void shouldConstructWithDefaultFilterAndAllowDynamicFilterSelection() throws IOException {
        DynamicAuthFilter dynamicFilter = new DynamicAuthFilter(defaultFilter);

        assertSame(defaultFilter, dynamicFilter.getAuthFilter());

        dynamicFilter.filter(requestContext);

        verify(defaultFilter).filter(requestContext);
        verifyNoInteractions(newFilter);

        dynamicFilter.setAuthFilter(newFilter);

        assertSame(newFilter, dynamicFilter.getAuthFilter());
        assertNotSame(defaultFilter, dynamicFilter.getAuthFilter());

        dynamicFilter.filter(requestContext);

        verify(newFilter).filter(requestContext);
        verify(defaultFilter, times(1)).filter(requestContext);
    }
}
