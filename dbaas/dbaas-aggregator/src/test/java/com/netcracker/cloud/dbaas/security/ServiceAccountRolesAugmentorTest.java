package com.netcracker.cloud.dbaas.security;

import com.netcracker.cloud.dbaas.Constants;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceAccountRolesAugmentorTest {
    @Mock
    ServiceAccountRolesManager rolesManager;
    @Mock
    SecurityIdentity mockIdentity;
    @Mock
    Principal mockPrincipal;

    @InjectMocks
    ServiceAccountRolesAugmentor augmentor;

    @Test
    void augment() {
        Map<String, Set<String>> serviceAccounts = new HashMap<>();

        serviceAccounts.put(
                "service-account-1",
                Set.of("NAMESPACE_CLEANER", "DB_CLIENT", "MIGRATION_CLIENT")
        );

        serviceAccounts.put(
                "service-account-2",
                Set.of("NAMESPACE_CLEANER", "MIGRATION_CLIENT")
        );

        when(mockIdentity.getPrincipal()).thenReturn(mockPrincipal);

        serviceAccounts.forEach((name, roles) -> {
            when(rolesManager.getRolesByServiceAccountName(name)).thenReturn(roles);
            when(mockPrincipal.getName()).thenReturn(name);

            Uni<SecurityIdentity> identityUni = augmentor.augment(mockIdentity, null);
            assertEquals(identityUni.await().indefinitely().getRoles(), roles);

            when(mockPrincipal.getName()).thenReturn("someOtherName");
            identityUni = augmentor.augment(mockIdentity, null);
            assertEquals(identityUni.await().indefinitely().getRoles(), Set.of(Constants.DB_CLIENT));
        });
    }
}
