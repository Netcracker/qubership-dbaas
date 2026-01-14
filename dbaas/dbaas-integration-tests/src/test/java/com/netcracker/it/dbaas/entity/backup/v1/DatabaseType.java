package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.Getter;

@Getter
public enum DatabaseType {
    POSTGRESQL("postgresql"),
    ARANGODB("arangodb"),
    CLICKHOUSE("clickhouse"),
    MONGODB("mongodb"),
    CASSANDRA("cassandra");

    private final String type;

    DatabaseType(String type) {
        this.type = type;
    }

}
