package com.netcracker.cloud.dbaas.testapp.config;

import com.netcracker.cloud.security.core.auth.DummyM2MManager;
import com.netcracker.cloud.security.core.auth.M2MManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security lands on the classpath transitively via the dbaas REST client and would
 * otherwise secure every endpoint with HTTP Basic (401). This is a black-box test app with no
 * auth, so permit all requests and disable CSRF so the POST/DELETE item endpoints work.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The dbaas REST client's M2M OkHttp flavor (selected in the direct-M2M phase via
     * {@code dbaas.restclient.resttemplate.basic-auth=false}) autowires an {@link M2MManager}. This
     * app has no platform gateway/Keycloak; for direct dbaas calls the aggregator is authenticated
     * with the pod's projected {@code dbaas}-audience token (not this manager's token), so a dummy
     * manager satisfies the dependency. Guarded by {@link ConditionalOnMissingBean} so a real
     * manager wins if one is ever present, and harmless in the basic-auth phases (the bean is unused).
     */
    @Bean
    @ConditionalOnMissingBean(M2MManager.class)
    public M2MManager m2mManager() {
        return new DummyM2MManager();
    }
}
