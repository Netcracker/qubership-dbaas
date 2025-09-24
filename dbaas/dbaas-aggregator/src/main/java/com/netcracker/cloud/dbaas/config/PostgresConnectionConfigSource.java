package com.netcracker.cloud.dbaas.config;

import com.netcracker.cloud.dbaas.JdbcUtils;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PostgresConnectionConfigSource implements ConfigSource {
    private final Map<String, String> properties = new HashMap<>();

    public PostgresConnectionConfigSource() {
        properties.put("quarkus.datasource.jdbc.url", JdbcUtils.resolveConnectionURL());
        properties.put("quarkus.datasource.username", JdbcUtils.resolveUsername());
        properties.put("quarkus.datasource.password", JdbcUtils.resolvePassword());
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of("quarkus.datasource.jdbc.url",
                "quarkus.datasource.username",
                "quarkus.datasource.password");
    }

    @Override
    public String getValue(String propertyName) {
        if (!getPropertyNames().contains(propertyName)) {
            return null;
        }
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() {
        return "PostgresConnectionConfigSource";
    }
}
