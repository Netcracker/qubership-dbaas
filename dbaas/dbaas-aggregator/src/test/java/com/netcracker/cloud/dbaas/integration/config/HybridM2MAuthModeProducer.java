package com.netcracker.cloud.dbaas.integration.config;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@Alternative
@ApplicationScoped
public class HybridM2MAuthModeProducer {
    @Produces
    @Singleton
    M2MAuthMode m2mAuthMode() {
        return M2MAuthMode.HYBRID;
    }
}
