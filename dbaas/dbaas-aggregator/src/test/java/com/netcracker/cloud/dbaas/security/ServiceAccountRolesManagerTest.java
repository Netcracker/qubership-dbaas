package com.netcracker.cloud.dbaas.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ServiceAccountRolesManagerTest {
    ServiceAccountRolesManager serviceAccountRolesManager;

    @BeforeEach
    void setUp() {
        serviceAccountRolesManager = new ServiceAccountRolesManager();
        serviceAccountRolesManager.onStart(null, "./src/test/resources/service-account-roles-secret.yaml");
    }

    @Test
    void getRolesByServiceAccountName() {
        Set<String> roles0 = serviceAccountRolesManager.getRolesByServiceAccountName("service-account-1");
        assertArrayEquals(new String[]{"NAMESPACE_CLEANER", "DB_CLIENT", "MIGRATION_CLIENT"}, roles0.toArray());

        Set<String> roles1 = serviceAccountRolesManager.getRolesByServiceAccountName("service-account-2");
        assertArrayEquals(new String[]{"NAMESPACE_CLEANER", "MIGRATION_CLIENT"}, roles1.toArray());
    }
}
