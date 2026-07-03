package com.netcracker.cloud.dbaas.security;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Slf4j
public class GlobalPermissionsConfigLoader {

    @Getter
    private Map<String, List<String>> globalPermissionConfiguration;

    void loadGlobalPermissionsConfig(@Observes StartupEvent event,
                                     @ConfigProperty(name = "dbaas.global-permissions.configuration.location") String location) throws IOException {
StructuredLog.info(log, "Start global permissions configuration loading from", "location", location);
        InputStream configStream = getClass().getResourceAsStream(location);
        if (configStream == null) {
            configStream = FileUtils.openInputStream(FileUtils.getFile(location));
        }
        globalPermissionConfiguration = new ObjectMapper().readValue(configStream, new TypeReference<>() {
        });
        configStream.close();
StructuredLog.info(log, "global permissions loaded", "count", globalPermissionConfiguration.size());
    }
}
