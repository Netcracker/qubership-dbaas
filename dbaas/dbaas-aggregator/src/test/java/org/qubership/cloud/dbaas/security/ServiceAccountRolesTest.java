package org.qubership.cloud.dbaas.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ServiceAccountRolesTest {
    ServiceAccountRoles serviceAccountRoles;

    @BeforeEach
    void setUp() throws IOException {
        String rawRolesSecret = Files.readString(Path.of("./src/test/resources/" + "roles-secret.yaml"));
        serviceAccountRoles = new ServiceAccountRoles(rawRolesSecret);
        serviceAccountRoles.onStart(null);
    }

    @Test
    void getRolesByServiceAccountName() {
        List<String> roles0 = serviceAccountRoles.getRolesByServiceAccountName("service-account-1");
        assertArrayEquals(new String[]{"NAMESPACE_CLEANER", "DB_CLIENT", "MIGRATION_CLIENT"}, roles0.toArray());

        List<String> roles1 = serviceAccountRoles.getRolesByServiceAccountName("service-account-2");
        assertArrayEquals(new String[]{"NAMESPACE_CLEANER", "MIGRATION_CLIENT"}, roles1.toArray());
    }
}
