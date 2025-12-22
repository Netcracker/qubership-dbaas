package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;


/**
 * Contains DBaaS users data, that was read from {@code dbaas-security-configuration-volume}.
 */
@Data
@AllArgsConstructor
public class DbaasUsersData {
    /**
     * Map that contains parsed users.json:
     * Key - username;
     * Value - password and list of roles.
     */
    private final Map<String, UserData> users;

    public String getBasicAuthorizationForRoles(String... roles) {
        String user = null;
        String password = null;
        for (Map.Entry<String, DbaasUsersData.UserData> e : users.entrySet()) {
            DbaasUsersData.UserData value = e.getValue();
            if (value.getRoles() != null && value.getRoles().containsAll(Arrays.asList(roles))) {
                user = e.getKey();
                password = value.getPassword();
                break;
            }
        }
        assertNotNull(user);
        assertNotNull(password);
        return Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    @Data
    public static class UserData {
        private String password;
        private List<String> roles;
    }
}
