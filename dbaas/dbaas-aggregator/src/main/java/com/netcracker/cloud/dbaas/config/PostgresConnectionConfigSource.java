package com.netcracker.cloud.dbaas.config;

import com.netcracker.cloud.dbaas.JdbcUtils;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PostgresConnectionConfigSource implements ConfigSource {
    private final Map<String, String> properties = new HashMap<>();

    public PostgresConnectionConfigSource() {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String database = System.getenv().getOrDefault("POSTGRES_DATABASE", "dbaas");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "dbaas");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "dbaas");

        String url = JdbcUtils.buildConnectionURL(host, port, database);

        properties.put("quarkus.datasource.jdbc.url", url);
        properties.put("postgresql.user", user);
        properties.put("postgresql.password", password);
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of("quarkus.datasource.jdbc.url",
                "postgresql.user",
                "postgresql.password");
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
