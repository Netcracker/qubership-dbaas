package com.netcracker.it.dbaas.helpers;

import com.netcracker.it.dbaas.entity.Role;

import java.util.*;

public class ConnectionPropertiesUtils {

    private ConnectionPropertiesUtils() {
    }

    public static Optional<Map<String, Object>> getConnectionProperties(List<Map<String, Object>> properties, Role role) {
        if (properties == null) {
            return Optional.empty();
        }
        return properties.stream().filter(v -> v.containsKey("role") && (role.getRoleValue().equals(v.get("role")) || role.equals(v.get("role")))).findFirst();
    }

    public static Optional<Map<String, Object>> getConnectionProperties(List<Map<String, Object>> properties, String role) {
        if (properties == null) {
            return Optional.empty();
        }
        return properties.stream().filter(v -> v.containsKey("role") && (role.equals(v.get("role")) || role.equals(v.get("role")))).findFirst();
    }

    public static List<Map<String, Object>> createCopy(List<Map<String, Object>> properties) {
        List<Map<String, Object>> resultProperties = new ArrayList<>();
        properties.forEach(v -> resultProperties.add(new HashMap<>(v)));
        return resultProperties;
    }

    public static Map<String, Object> addRoleIfNotPresent(Map<String, Object> connectionProperties, Role role) {
        HashMap<String, Object> connectionPropertiesWithRole = new HashMap<>(connectionProperties);
        if (!connectionProperties.containsKey("role")) {
            connectionPropertiesWithRole.put("role", role);
        }
        return connectionPropertiesWithRole;
    }

    public static Map<String, Object> addRoleIfNotPresent(Map<String, Object> connectionProperties) {
        HashMap<String, Object> connectionPropertiesWithRole = new HashMap<>(connectionProperties);
        if (!connectionProperties.containsKey("role")) {
            connectionPropertiesWithRole.put("role", Role.ADMIN);
        }
        return connectionPropertiesWithRole;
    }
}
