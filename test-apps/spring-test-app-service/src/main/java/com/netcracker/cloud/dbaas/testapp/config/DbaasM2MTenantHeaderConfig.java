package com.netcracker.cloud.dbaas.testapp.config;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.framework.contexts.tenant.BaseTenantProvider;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import com.netcracker.cloud.restclient.HttpMethod;
import com.netcracker.cloud.restclient.MicroserviceRestClient;
import com.netcracker.cloud.restclient.entity.RestClientResponseEntity;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TEMPORARY workaround for the direct-M2M phase (Pass C).
 *
 * <p>The dbaas Spring client's M2M flavor is an {@code OkHttp} client built by
 * {@code M2MClientFactory.getDbaasOkHttpClient}. Unlike the RestTemplate transport
 * ({@code SpringRestTemplateInterceptor}) and the Quarkus transport
 * ({@code QuarkusRestClientInterceptor}), the OkHttp transport has no context-propagation
 * interceptor, so the {@code Tenant} header is not forwarded. The aggregator then rejects
 * tenant-scoped {@code GetOrCreateDatabase} calls with {@code CORE-DBAAS-4054}
 * ("tenantId from classifier 'acme' and tenantId from request '' don't match"), because it reads
 * the request tenant from its {@code TenantContext} (populated from the {@code Tenant} header) —
 * and that check runs only for K8s-JWT (M2M) callers.
 *
 * <p>This decorator re-adds the {@code Tenant} header from the current {@link TenantContextObject}
 * (pinned by the controller via {@code ContextManager.set}) to every {@code dbaasRestClient}
 * request. Remove once OkHttp context propagation lands in the dbaas client library.
 */
@Configuration
public class DbaasM2MTenantHeaderConfig {

    static final String DBAAS_REST_CLIENT_BEAN = "dbaasRestClient";

    @Bean
    static BeanPostProcessor dbaasRestClientTenantHeaderPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (DBAAS_REST_CLIENT_BEAN.equals(beanName)
                        && bean instanceof MicroserviceRestClient client
                        && !(bean instanceof TenantHeaderRestClient)) {
                    return new TenantHeaderRestClient(client);
                }
                return bean;
            }
        };
    }

    /** Delegating {@link MicroserviceRestClient} that injects the current {@code Tenant} header. */
    static final class TenantHeaderRestClient implements MicroserviceRestClient {

        private final MicroserviceRestClient delegate;

        TenantHeaderRestClient(MicroserviceRestClient delegate) {
            this.delegate = delegate;
        }

        private static String currentTenant() {
            try {
                TenantContextObject ctx = ContextManager.get(BaseTenantProvider.TENANT_CONTEXT_NAME);
                return ctx == null ? null : ctx.getTenant();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static Map<String, List<String>> withTenant(Map<String, List<String>> headers) {
            String tenant = currentTenant();
            if (tenant == null || tenant.isEmpty()) {
                return headers;
            }
            Map<String, List<String>> merged = headers == null ? new HashMap<>() : new HashMap<>(headers);
            merged.putIfAbsent(TenantContextObject.TENANT_HEADER, new ArrayList<>(List.of(tenant)));
            return merged;
        }

        @Override
        public <T> RestClientResponseEntity<T> doRequest(String url, HttpMethod httpMethod, Map<String, List<String>> headers,
                                                         Object requestBody, Class<T> responseClass, Map<String, Object> params) {
            return delegate.doRequest(url, httpMethod, withTenant(headers), requestBody, responseClass, params);
        }

        @Override
        public <T> RestClientResponseEntity<T> doRequest(String url, HttpMethod httpMethod, Map<String, List<String>> headers,
                                                         Object requestBody, Class<T> responseClass) {
            return delegate.doRequest(url, httpMethod, withTenant(headers), requestBody, responseClass);
        }

        @Override
        public <T> RestClientResponseEntity<T> doRequest(URI uri, HttpMethod httpMethod, Map<String, List<String>> headers,
                                                         Object requestBody, Class<T> responseClass) {
            return delegate.doRequest(uri, httpMethod, withTenant(headers), requestBody, responseClass);
        }
    }
}
