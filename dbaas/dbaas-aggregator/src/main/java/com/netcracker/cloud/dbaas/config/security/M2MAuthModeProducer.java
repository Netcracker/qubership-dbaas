package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class M2MAuthModeProducer {
    @Produces
    @Singleton
    M2MAuthMode m2mAuthMode() {
        return M2MAuthMode.readFromEnv();
    }
}
