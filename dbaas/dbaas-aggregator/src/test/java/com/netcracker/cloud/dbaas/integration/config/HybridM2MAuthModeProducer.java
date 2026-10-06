package com.netcracker.cloud.dbaas.integration.config;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Replaces {@code M2MAuthModeProducer} in tests that need Kubernetes tokens, without setting {@code M2M_AUTH_MODE}
 * in the test JVM. Enabled per test profile through {@code getEnabledAlternatives()}, so other tests keep the default.
 */
@Alternative
@ApplicationScoped
public class HybridM2MAuthModeProducer {
    @Produces
    @Singleton
    M2MAuthMode m2mAuthMode() {
        return M2MAuthMode.HYBRID;
    }
}
