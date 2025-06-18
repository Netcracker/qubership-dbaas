package org.qubership.cloud.dbaas.connections.handlers;

import org.qubership.cloud.dbaas.DatabaseType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConnectionHandler {

    Optional<String> getHost(List<Map<String, Object>> connectionProperties);

    DatabaseType getPhysDbType();
}
