package com.netcracker.cloud.dbaas.test.config;

import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.mockito.Mockito;
import com.netcracker.core.scheduler.po.ProcessOrchestrator;

@Dependent
public class ProcessOrchestratorConfiguration {
    @Produces
    @Singleton
    @Startup
    @UnlessBuildProperty(name = "dbaas.process-orchestrator.enabled", stringValue = "true")
    public ProcessOrchestrator processOrchestrator() {
        return Mockito.mock(ProcessOrchestrator.class);
    }
}
