package com.netcracker.cloud.dbaas.security;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoExtension;
import com.netcracker.cloud.dbaas.Constants;
import com.netcracker.cloud.dbaas.dto.role.ServiceAccountWithRoles;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void augment() {
        ArrayList<ServiceAccountWithRoles> serviceAccounts = new ArrayList<>();

        serviceAccounts.add(new ServiceAccountWithRoles(
                "service-account-1",
                Set.of("NAMESPACE_CLEANER", "DB_CLIENT", "MIGRATION_CLIENT")
        ));

        serviceAccounts.add(new ServiceAccountWithRoles(
                "service-account-2",
                Set.of("NAMESPACE_CLEANER", "MIGRATION_CLIENT")
        ));

        when(mockIdentity.getPrincipal()).thenReturn(mockPrincipal);

        for (ServiceAccountWithRoles sa : serviceAccounts) {
            when(rolesManager.getRolesByServiceAccountName(sa.getName())).thenReturn(sa.getRoles());
            when(mockPrincipal.getName()).thenReturn(sa.getName());

            Uni<SecurityIdentity> identityUni = augmentor.augment(mockIdentity, null);
            assertEquals(identityUni.await().indefinitely().getRoles(), sa.getRoles());

            when(mockPrincipal.getName()).thenReturn("someOtherName");
            identityUni = augmentor.augment(mockIdentity, null);
            assertEquals(identityUni.await().indefinitely().getRoles(), Set.of(Constants.DB_CLIENT));
        }
    }
}
