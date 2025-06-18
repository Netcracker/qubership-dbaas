package org.qubership.cloud.dbaas.connections.handlers;

import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DefaultConnectionHandler implements ConnectionHandler {

    private static final String HOST_ELEMENT = "host";

    @Override
    public Optional<String> getHost(List<Map<String, Object>> connectionProperties) {
        return Optional.ofNullable(
                (String) ConnectionPropertiesUtils.getSafeConnectionProperties(connectionProperties, Role.ADMIN.toString())
                        .orElse(Collections.emptyMap())
                        .get(HOST_ELEMENT)
        );
    }

    @Override
    public DatabaseType getPhysDbType() {
        return DatabaseType.UNKNOWN;
    }
}
