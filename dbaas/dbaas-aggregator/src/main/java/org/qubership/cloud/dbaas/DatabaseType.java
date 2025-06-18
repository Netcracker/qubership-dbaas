package org.qubership.cloud.dbaas;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum DatabaseType {

    POSTGRESQL("postgresql"),
    CASSANDRA("cassandra"),
    REDIS("redis"),
    OPENSEARCH("opensearch"),
    ARANGODB("arangodb"),
    UNKNOWN("unknown");

    private final String type;

    @Override
    public String toString() {
        return type;
    }

    DatabaseType(String type) {
        this.type = type;
    }


    @JsonCreator
    public static DatabaseType fromString(String text) {
        for (DatabaseType r : DatabaseType.values()) {
            if (r.toString().equalsIgnoreCase(text)) {
                return r;
            }
        }
        return UNKNOWN;
    }
}
