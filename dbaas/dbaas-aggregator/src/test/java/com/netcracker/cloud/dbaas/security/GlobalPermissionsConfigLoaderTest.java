package com.netcracker.cloud.dbaas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class GlobalPermissionsConfigLoaderTest {
    @TempDir
    private Path tempDir;

    @Test
    void loadGlobalPermissionsConfig() throws IOException {
        Map<String, List<String>> globalPermissionConfig = Map.of(
                "service1", List.of("role1"),
                "service2", List.of("role2", "role3")
        );

        final String location = tempDir.resolve("services.json").toAbsolutePath().toString();
        try (FileOutputStream out = new FileOutputStream(location)) {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.writeValue(out, globalPermissionConfig);
        }

        GlobalPermissionsConfigLoader loader = new GlobalPermissionsConfigLoader();
        loader.loadGlobalPermissionsConfig(null, location);

        Assertions.assertEquals(globalPermissionConfig, loader.getGlobalPermissionConfiguration());
    }
}
