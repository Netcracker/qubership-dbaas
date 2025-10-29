package com.netcracker.cloud.dbaas.service;

import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabase;
import com.netcracker.cloud.dbaas.repositories.dbaas.PhysicalDatabaseDbaasRepository;
import com.netcracker.cloud.dbaas.rest.DbaasAdapterRestClient;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.ext.QueryParamStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.URL;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupPhysicalDbRegistrationServiceTest {

    @Mock
    PhysicalDatabaseDbaasRepository repository;


    Map<String, DbaasAdapterRestClient> mockedRestClients = new HashMap<>();

    @AfterEach
    void clearMocks() {
        mockedRestClients.clear();
    }

    @Test
    void testThreeAdapters() {
        Mockito.mockStatic(RestClientBuilder.class).when(RestClientBuilder::newBuilder).thenReturn(new StubRestClientBuilder(mockedRestClients));

        when(repository.findByAdapterAddress(eq("http://adapter1:8080"))).thenReturn(new PhysicalDatabase());

        new StartupPhysicalDbRegistrationService(repository, "http://fake:8080,http://adapter1:8080,http://adapter2:8080,");

        verify(repository, times(3)).findByAdapterAddress(any());
        DbaasAdapterRestClient fakeAdapterRestClient = mockedRestClients.get("fake");
        assertNotNull(fakeAdapterRestClient);
        assertNull(mockedRestClients.get("adapter1"));
        DbaasAdapterRestClient adapter2RestClient = mockedRestClients.get("adapter2");
        assertNotNull(mockedRestClients.get("adapter2"));

        verify(fakeAdapterRestClient).forceRegistration();
        verify(adapter2RestClient).forceRegistration();
    }

    @Test
    void testBlankVariable() {
        new StartupPhysicalDbRegistrationService(repository, "  , ");

        verify(repository, times(0)).findByAdapterAddress(any());
        assertTrue(mockedRestClients.isEmpty());
    }

    @Test
    void testNullVariable() {
        new StartupPhysicalDbRegistrationService(repository, null);

        verify(repository, times(0)).findByAdapterAddress(any());
        assertTrue(mockedRestClients.isEmpty());
    }

    static class StubRestClientBuilder implements RestClientBuilder {
        private URL baseUrl;
        private Map<String, DbaasAdapterRestClient> mockedRestClients;

        public StubRestClientBuilder(Map<String, DbaasAdapterRestClient> mockedRestClients) {
            this.mockedRestClients = mockedRestClients;
        }

        @Override
        public RestClientBuilder baseUrl(URL url) {
            this.baseUrl = url;
            return this;
        }

        @Override
        public <T> T build(Class<T> aClass) throws IllegalStateException, RestClientDefinitionException {
            DbaasAdapterRestClient restClient = Mockito.mock(DbaasAdapterRestClient.class);
            mockedRestClients.put(baseUrl.getHost(), restClient);
            when(restClient.forceRegistration()).thenAnswer(invocationOnMock -> {
                if (baseUrl.getHost().contains("fake")) {
                    throw new RuntimeException("Exception occurred while notifying fake adapter!");
                }
                return Response.accepted().build();
            });
            return (T) restClient;
        }

        @Override
        public RestClientBuilder connectTimeout(long l, java.util.concurrent.TimeUnit timeUnit) {
            return null;
        }

        @Override
        public RestClientBuilder readTimeout(long l, java.util.concurrent.TimeUnit timeUnit) {
            return null;
        }

        @Override
        public RestClientBuilder executorService(ExecutorService executorService) {
            return null;
        }

        @Override
        public RestClientBuilder sslContext(SSLContext sslContext) {
            return null;
        }

        @Override
        public RestClientBuilder trustStore(KeyStore keyStore) {
            return null;
        }

        @Override
        public RestClientBuilder keyStore(KeyStore keyStore, String s) {
            return null;
        }

        @Override
        public RestClientBuilder hostnameVerifier(HostnameVerifier hostnameVerifier) {
            return null;
        }

        @Override
        public RestClientBuilder followRedirects(boolean b) {
            return null;
        }

        @Override
        public RestClientBuilder proxyAddress(String s, int i) {
            return null;
        }

        @Override
        public RestClientBuilder queryParamStyle(QueryParamStyle queryParamStyle) {
            return null;
        }

        @Override
        public RestClientBuilder header(String s, Object o) {
            return null;
        }

        @Override
        public Configuration getConfiguration() {
            return null;
        }

        @Override
        public RestClientBuilder property(String s, Object o) {
            return null;
        }

        @Override
        public RestClientBuilder register(Class<?> aClass) {
            return null;
        }

        @Override
        public RestClientBuilder register(Class<?> aClass, int i) {
            return null;
        }

        @Override
        public RestClientBuilder register(Class<?> aClass, Class<?>... classes) {
            return null;
        }

        @Override
        public RestClientBuilder register(Class<?> aClass, Map<Class<?>, Integer> map) {
            return null;
        }

        @Override
        public RestClientBuilder register(Object o) {
            return null;
        }

        @Override
        public RestClientBuilder register(Object o, int i) {
            return null;
        }

        @Override
        public RestClientBuilder register(Object o, Class<?>... classes) {
            return null;
        }

        @Override
        public RestClientBuilder register(Object o, Map<Class<?>, Integer> map) {
            return null;
        }
    }
}
