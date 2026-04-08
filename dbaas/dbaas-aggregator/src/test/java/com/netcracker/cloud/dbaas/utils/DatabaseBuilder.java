package com.netcracker.cloud.dbaas.utils;

import com.netcracker.cloud.dbaas.DatabaseType;
import com.netcracker.cloud.dbaas.entity.pg.Database;
import com.netcracker.cloud.dbaas.entity.pg.DatabaseRegistry;
import com.netcracker.cloud.dbaas.entity.pg.DbResource;
import com.netcracker.cloud.dbaas.entity.pg.DbState;
import com.netcracker.cloud.dbaas.entity.shared.AbstractDbState;

import java.util.*;
import java.util.function.Consumer;

import static com.netcracker.cloud.dbaas.Constants.*;

@SuppressWarnings("deprecation")
public class DatabaseBuilder {
    public static final String POSTGRES_ADAPTER_ID = UUID.randomUUID().toString();
    public static final String POSTGRES_PHY_DB_ID = "postgres-adapter";
    public static final String TEST_MS = "test-ms";
    public static final String TEST_NS = "test-ns";

    private final Database database;
    private final List<DatabaseRegistry> registries = new ArrayList<>();

    private final SortedMap<String, Object> classifier;
    private String type = DatabaseType.POSTGRESQL.toString();

    public DatabaseBuilder() {
        database = new Database();
        database.setId(UUID.randomUUID());
        database.setName("test-db-" + database.getId());
        database.setType(type);
        database.setPhysicalDatabaseId(POSTGRES_PHY_DB_ID);
        database.setAdapterId(POSTGRES_ADAPTER_ID);
        classifier = new TreeMap<>(Map.of(
                SCOPE, "service",
                NAMESPACE, TEST_NS,
                MICROSERVICE_NAME, TEST_MS,
                "random", UUID.randomUUID().toString()));
        database.setClassifier(classifier);
        DbResource dbResource = new DbResource();
        database.setNamespace(TEST_NS);
        dbResource.setId(UUID.randomUUID());
        database.setResources(new ArrayList<>(List.of(dbResource)));
        database.setConnectionProperties(new ArrayList<>(List.of(Map.of(
                "username", "username",
                "password", "password"
        ))));
        database.setDbState(new DbState(AbstractDbState.DatabaseStateStatus.CREATED));
    }

    public DatabaseBuilder id(UUID id) {
        database.setId(id);
        return this;
    }

    public DatabaseBuilder name(String name) {
        database.setName(name);
        return this;
    }

    public DatabaseBuilder type(String type) {
        this.type = type;
        database.setType(type);
        return this;
    }

    public DatabaseBuilder classifier(String key, Object value) {
        classifier.put(key, value);
        return this;
    }

    public DatabaseBuilder classifier(Map<String, Object> classifier) {
        this.classifier.clear();
        this.classifier.putAll(classifier);
        return this;
    }

    public DatabaseBuilder resource(DbResource resource) {
        database.getResources().add(resource);
        return this;
    }

    public DatabaseBuilder resources(List<DbResource> resources) {
        database.getResources().clear();
        database.getResources().addAll(resources);
        return this;
    }

    public DatabaseBuilder connectionProperty(Map<String, Object> connectionProperties) {
        database.getConnectionProperties().add(connectionProperties);
        return this;
    }

    public DatabaseBuilder connectionProperties(List<Map<String, Object>> connectionProperties) {
        database.getConnectionProperties().clear();
        database.getConnectionProperties().addAll(connectionProperties);
        return this;
    }

    public DatabaseBuilder state(AbstractDbState.DatabaseStateStatus state) {
        database.getDbState().setDatabaseState(state);
        return this;
    }

    public DatabaseBuilder external(boolean external) {
        database.setExternallyManageable(external);
        if (external) {
            resources(List.of());
            database.setAdapterId(null);
        }
        return this;
    }

    public DatabaseBuilder adapterId(String adapterId) {
        database.setAdapterId(adapterId);
        return this;
    }

    public DatabaseBuilder physicalDatabaseId(String physicalDatabaseId) {
        database.setPhysicalDatabaseId(physicalDatabaseId);
        return this;
    }

    public DatabaseBuilder registry() {
        return registry(b -> b.classifier("random", UUID.randomUUID().toString()));
    }

    public DatabaseBuilder registry(Consumer<DatabaseRegistryBuilder> registryConfig) {
        DatabaseRegistryBuilder builder = new DatabaseRegistryBuilder();
        registryConfig.accept(builder);
        builder.type(type);
        builder.classifier(MICROSERVICE_NAME, classifier.get(MICROSERVICE_NAME));
        registries.add(builder.build());
        return this;
    }

    public DatabaseBuilder registry(DatabaseRegistry registry) {
        registry.setType(type);
        registries.add(registry);
        return this;
    }

    public Database build() {
        database.setDatabaseRegistry(registries);
        registries.forEach(registry -> registry.setDatabase(database));
        return database;
    }


    public static class DatabaseRegistryBuilder {
        private final DatabaseRegistry registry;
        private final SortedMap<String, Object> classifier;

        private DatabaseRegistryBuilder() {
            registry = new DatabaseRegistry();
            classifier = new TreeMap<>(Map.of(
                    SCOPE, "service",
                    NAMESPACE, TEST_NS,
                    MICROSERVICE_NAME, TEST_MS));
            registry.setClassifier(classifier);
            registry.setNamespace(TEST_NS);
        }

        public DatabaseRegistryBuilder id(UUID id) {
            registry.setId(id);
            return this;
        }

        public DatabaseRegistryBuilder namespace(String namespace) {
            registry.setNamespace(namespace);
            classifier.put(NAMESPACE, namespace);
            return this;
        }

        public DatabaseRegistryBuilder type(String type) {
            registry.setType(type);
            return this;
        }

        public DatabaseRegistryBuilder classifier(String key, Object value) {
            classifier.put(key, value);
            return this;
        }

        public DatabaseRegistry build() {
            return registry;
        }
    }
}
