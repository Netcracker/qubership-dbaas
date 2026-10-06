package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Single source of the M2M authentication mode for dbaas-aggregator: the {@code M2M_AUTH_MODE} environment variable,
 * parsed by {@link M2MAuthMode#readFromEnv()} exactly as the M2M client libraries parse it.
 * <p>
 * There is deliberately no config property for the mode, so it cannot be overridden by system properties, environment
 * aliases or {@code application.properties}. Tests select a different mode by enabling an {@code @Alternative} producer
 * through {@code QuarkusTestProfile#getEnabledAlternatives()}.
 */
@ApplicationScoped
@Slf4j
public class M2MAuthModeProducer {

    /**
     * {@link M2MAuthMode} is an enum and cannot be proxied, hence {@code @Singleton} rather than a normal scope.
     * An unsupported value fails here; {@link KubernetesJWTCallerPrincipalFactory} is created at startup, so the
     * service does not start.
     */
    @Produces
    @Singleton
    M2MAuthMode m2mAuthMode() {
        M2MAuthMode mode = M2MAuthMode.readFromEnv();
        log.info("M2M auth mode: {}", mode);
        return mode;
    }

    /**
     * Whether dbaas-aggregator accepts Kubernetes tokens on its API and sends them to adapters.
     * Hybrid and k8s are the same here: the aggregator keeps accepting Basic credentials in every mode.
     */
    public static boolean isKubernetesTokenEnabled(M2MAuthMode mode) {
        return switch (mode) {
            case LEGACY -> false;
            case HYBRID, K8S -> true;
        };
    }
}
