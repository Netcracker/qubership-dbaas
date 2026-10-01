package com.netcracker.cloud.dbaas.config.security;

import com.netcracker.cloud.security.core.utils.k8s.M2MAuthMode;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Set;

public class M2MAuthModeConfigSource implements ConfigSource {
    static final String M2M_ENABLED_PROPERTY = "dbaas.security.k8s.m2m.enabled";

    private final Map<String, String> properties;

    public M2MAuthModeConfigSource() {
        this(M2MAuthMode.readFromEnv());
    }

    M2MAuthModeConfigSource(M2MAuthMode mode) {
        boolean kubernetesTokenAccepted = switch (mode) {
            case LEGACY -> false;
            case HYBRID, K8S -> true;
        };
        this.properties = Map.of(M2M_ENABLED_PROPERTY, String.valueOf(kubernetesTokenAccepted));
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return M2MAuthModeConfigSource.class.getSimpleName();
    }
}
