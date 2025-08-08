package org.qubership.cloud.dbaas.service;

import org.qubership.cloud.dbaas.dto.role.Role;
import org.qubership.cloud.dbaas.exceptions.NotExistingConnectionPropertiesException;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.cloud.dbaas.Constants.ROLE;
import static org.qubership.cloud.dbaas.service.PasswordEncryption.PASSWORD_FIELD;

@Slf4j
public class ConnectionPropertiesUtils {

    private ConnectionPropertiesUtils() {
    }

    public static final Integer ROLE_NOT_FOUND = -1;


    public static Optional<Map<String, Object>> getConnectionProperties(List<Map<String, Object>> properties, Role role) {
        if (properties == null) {
            return Optional.empty();
        }
        return properties.stream().filter(v -> v.containsKey(ROLE) && (role.toString().equals(v.get(ROLE)) || role.equals(v.get(ROLE)))).findFirst();
    }

    public static Map<String, Object> getConnectionProperties(List<Map<String, Object>> properties, String role) {
        return getSafeConnectionProperties(properties, role).orElseThrow(() -> new NotExistingConnectionPropertiesException(role));
    }

    public static Optional<Map<String, Object>> getSafeConnectionProperties(List<Map<String, Object>> properties, String role) {
        if (properties == null) {
            return Optional.empty();
        }
        log.debug("get connectionProperties from {}", toStringWithMaskedPassword(properties));
        return properties.stream().filter(v -> v.containsKey(ROLE) &&
                (role.equalsIgnoreCase((String) v.get(ROLE)))).findFirst();
    }

    public static Map<String, Object> addAdminRoleIfNotPresent(Map<String, Object> connectionProperties) {
        HashMap<String, Object> connectionPropertiesWithRole = new HashMap<>(connectionProperties);
        if (!connectionProperties.containsKey(ROLE)) {
            connectionPropertiesWithRole.put(ROLE, Role.ADMIN.toString());
        }
        return connectionPropertiesWithRole;
    }

    public static boolean checkRoleExistence(String role, List<Map<String, Object>> properties) {
        Optional<Map<String, Object>> connectionProperties = getSafeConnectionProperties(properties, role);
        return connectionProperties.isPresent();
    }


    public static List<Map<String, Object>> replaceConnectionProperties(String role, List<Map<String, Object>> properties, Map<String, Object> newProperty) {
        if (!checkRoleExistence(role, properties)) {
            log.debug("properties={} doesn't contain role={}", toStringWithMaskedPassword(properties), role);
            throw new NoSuchElementException("Property with role=" + role + " is not exist");
        }

        if (!checkRoleExistence(role, Collections.singletonList(newProperty))) {
            log.debug("newProperty={} doesn't contain role={}", newProperty, role);
            throw new NoSuchElementException("Property with role=" + role + " is not exist");
        }

        int elem = -1;
        for (int i = 0; i < properties.size(); i++) {
            Map<String, Object> currentProperties = properties.get(i);
            if (currentProperties.containsKey(ROLE) && role.equalsIgnoreCase((String) currentProperties.get(ROLE))) {
                elem = i;
                break;
            }
        }

        properties.set(elem, newProperty);
        return properties;
    }

    public static String toStringWithMaskedPassword(List<Map<String, Object>> connectionProperties) {
        if (null == connectionProperties || connectionProperties.isEmpty()) {
            return "";
        }
        return connectionProperties.stream()
                .map(cp -> cp.entrySet().stream()
                        .map(entry -> PASSWORD_FIELD.equalsIgnoreCase(entry.getKey())
                                ? PASSWORD_FIELD + "=***"
                                : entry.toString())
                        .collect(Collectors.joining(", ", "{", "}")))
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
