package org.qubership.cloud.dbaas.service;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@Slf4j
@ApplicationScoped
public class DbaaSHelper {
    private final boolean productionMode;
    private final String podName;

    public DbaaSHelper(@ConfigProperty(name = "dbaas.production.mode") boolean productionMode, @ConfigProperty(name = "dbaas.paas.pod-name") String podName) {
        this.productionMode = productionMode;
        this.podName = podName;
    }

    public boolean isProductionMode() {
        return productionMode;
    }

    public String getPodName() {
        return podName;
    }
}