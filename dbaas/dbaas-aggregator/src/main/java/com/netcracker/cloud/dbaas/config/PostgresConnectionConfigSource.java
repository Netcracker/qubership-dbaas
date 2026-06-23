package com.netcracker.cloud.dbaas.config;

import com.netcracker.cloud.dbaas.JdbcUtils;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PostgresConnectionConfigSource implements ConfigSource {

    private static final Set<String> PROPERTY_NAMES = Set.of(
        "quarkus.datasource.jdbc.url",
        "quarkus.datasource.username",
        "quarkus.datasource.password");

    @Override
    public Map<String, String> getProperties() {
        return resolve();
    }

    @Override
    public Set<String> getPropertyNames() {
        return PROPERTY_NAMES;
    }

    @Override
    public String getValue(String propertyName) {
        if (!PROPERTY_NAMES.contains(propertyName)) {
            return null;
        }
        return resolve().get(propertyName);
    }

    @Override
    public String getName() {
        return "PostgresConnectionConfigSource";
    }

    private Map<String, String> resolve() {
        Map<String, String> properties = new HashMap<>();
        properties.put("quarkus.datasource.jdbc.url", JdbcUtils.resolveConnectionURL());
        properties.put("quarkus.datasource.username", JdbcUtils.resolveUsername());
        properties.put("quarkus.datasource.password", JdbcUtils.resolvePassword());
        return Map.copyOf(properties);
    }
}
