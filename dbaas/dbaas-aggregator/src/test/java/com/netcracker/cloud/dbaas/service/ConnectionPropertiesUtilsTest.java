package org.qubership.cloud.dbaas.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.qubership.cloud.dbaas.service.ConnectionPropertiesUtils.toStringWithMaskedPassword;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionPropertiesUtilsTest {

    @Test
    void replaceConnectionPropertiesNewPropertyMustContainRole() {
        NoSuchElementException exception = assertThrows(NoSuchElementException.class, () -> ConnectionPropertiesUtils.replaceConnectionProperties(
                "admin",
                List.of(createConnectionProperties("user-a", "pwd-a", "admin")),
                createConnectionProperties("user-b", "pwd-b", null)
        ));
        Assertions.assertEquals("Property with role=admin is not exist", exception.getMessage());
    }

    @Test
    void replaceConnectionPropertiesPropertyMustContainRole() {
        NoSuchElementException exception = assertThrows(NoSuchElementException.class, () -> ConnectionPropertiesUtils.replaceConnectionProperties(
                "admin",
                List.of(createConnectionProperties("user-a", "pwd-a", null)),
                createConnectionProperties("user-b", "pwd-b", "admin")
        ));
        Assertions.assertEquals("Property with role=admin is not exist", exception.getMessage());
    }

    @Test
    void testToStringWithMaskedPassword() {
        List<Map<String, Object>> connectionProperties = List.of(createConnectionProperties("user", "pwd-$#&,.'\"()?a123U", "admin"));
        Assertions.assertTrue(toStringWithMaskedPassword(connectionProperties).contains("password=***,"));
        Assertions.assertEquals("", toStringWithMaskedPassword(Collections.emptyList()));
        Assertions.assertEquals("", toStringWithMaskedPassword(null));
    }

    private Map<String, Object> createConnectionProperties(String username, String password, String role) {
        Map<String, Object> connection = new HashMap<>();
        connection.put("username", username);
        connection.put("password", password);
        if (role != null) {
            connection.put("role", role);
        }
        return connection;
    }
}