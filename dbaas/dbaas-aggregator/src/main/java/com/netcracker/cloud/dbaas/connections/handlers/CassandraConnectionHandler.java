package org.qubership.cloud.dbaas.connections.handlers;

import org.qubership.cloud.dbaas.DatabaseType;
import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils;
import jakarta.enterprise.context.ApplicationScoped;

import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class CassandraConnectionHandler implements ConnectionHandler {

    private static final String CONTACT_POINTS_ELEMENT = "contactPoints";

    @Override
    public Optional<String> getHost(List<Map<String, Object>> connectionProperties) {
        List<String> contactPoints = (List) ConnectionPropertiesUtils
                .getSafeConnectionProperties(connectionProperties, Role.ADMIN.toString())
                .orElse(Collections.emptyMap())
                .get(CONTACT_POINTS_ELEMENT);

        return Optional.ofNullable(StringUtils.join(contactPoints, ","));
    }

    @Override
    public DatabaseType getPhysDbType() {
        return DatabaseType.CASSANDRA;
    }
}
