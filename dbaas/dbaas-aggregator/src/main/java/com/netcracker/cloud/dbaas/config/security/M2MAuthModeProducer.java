package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class M2MAuthModeProducer {

    // An enum cannot be proxied, so the bean is @Singleton rather than normal-scoped.
    @Produces
    @Singleton
    M2MAuthMode m2mAuthMode() {
        M2MAuthMode mode = M2MAuthMode.readFromEnv();
        log.info("M2M auth mode: {}", mode);
        return mode;
    }

    // Hybrid and k8s are the same here: the aggregator accepts Basic credentials in every mode.
    public static boolean isKubernetesTokenEnabled(M2MAuthMode mode) {
        return switch (mode) {
            case LEGACY -> false;
            case HYBRID, K8S -> true;
        };
    }
}
