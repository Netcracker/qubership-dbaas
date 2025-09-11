package com.netcracker.cloud.dbaas;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TestConfigSource implements ConfigSource {
    private static final String rolesSecretFilePath = "roles-secret.yaml";
    private static final String rolesConfigPropertyName = "roles.yaml";

    private final Map<String, String> properties;

    public TestConfigSource() throws IOException {
        try (InputStream inputStream = TestConfigSource.class.getClassLoader().getResourceAsStream(rolesSecretFilePath)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Kubernetes roles secret test file was not found" + "roles-secret.yaml");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String secretContent = reader.lines().collect(Collectors.joining(System.lineSeparator()));
                properties = Collections.singletonMap(rolesConfigPropertyName, secretContent);
            }
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
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
        return "TestConfigSource";
    }

    @Override
    public int getOrdinal() {
        return 500;
    }
}
