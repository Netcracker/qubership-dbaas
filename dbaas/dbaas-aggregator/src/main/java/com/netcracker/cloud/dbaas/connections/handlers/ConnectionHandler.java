package com.netcracker.cloud.dbaas.connections.handlers;

import com.netcracker.cloud.dbaas.DatabaseType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConnectionHandler {

    Optional<String> getHost(List<Map<String, Object>> connectionProperties);

    DatabaseType getPhysDbType();
}
