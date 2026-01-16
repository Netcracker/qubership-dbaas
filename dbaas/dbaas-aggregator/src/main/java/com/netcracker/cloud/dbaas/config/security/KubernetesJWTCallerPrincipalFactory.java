package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.KubernetesTokenVerificationException;
import com.netcracker.cloud.security.core.utils.k8s.KubernetesTokenVerifier;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.jwt.auth.principal.*;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Alternative
@Priority(1)
@Slf4j
public class KubernetesJWTCallerPrincipalFactory extends JWTCallerPrincipalFactory {

    private final KubernetesTokenVerifier verifier;

    @Inject
    public KubernetesJWTCallerPrincipalFactory(
            @ConfigProperty(name = "dbaas.security.k8s.jwt.enabled") boolean jwtEnabled,
            @ConfigProperty(name = "dbaas.security.k8s.jwt.audience") String jwtAudience
    ) {
        if (!jwtEnabled) {
            log.info("JWT not enabled, skipping verifier initialization");
            this.verifier = null;
            return;
        }

        log.info("Initializing KubernetesTokenVerifier");
        try {
            this.verifier = new KubernetesTokenVerifier(jwtAudience);
            log.info("KubernetesTokenVerifier initialized successfully");
        } catch (RuntimeException e) {
            log.error("Failed to initialize KubernetesTokenVerifier", e);
            throw e;
        }
    }

    KubernetesJWTCallerPrincipalFactory(KubernetesTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public JWTCallerPrincipal parse(String token, JWTAuthContextInfo authContextInfo) throws ParseException {
        try {
            return new DefaultJWTCallerPrincipal(verifier.verify(token));
        } catch (KubernetesTokenVerificationException e) {
            throw new ParseException("failed to parse kubernetes projected volume token", e);
        }
    }

    // observe StartupEvent so that bean is created at startup
    void onStartUp(@Observes StartupEvent event) {
    }
}
