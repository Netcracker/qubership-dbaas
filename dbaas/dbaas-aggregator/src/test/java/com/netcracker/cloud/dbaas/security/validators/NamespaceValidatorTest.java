package com.netcracker.cloud.dbaas.security.validators;

import com.netcracker.cloud.dbaas.Constants;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeStructure;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;
import com.netcracker.cloud.dbaas.utils.JwtUtils;
import io.smallrye.jwt.auth.principal.JWTCallerPrincipal;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NamespaceValidatorTest {
    private static final String defaultBaseLine = "default";
    private static final String defaultNamespace = "default";
    private static final String otherNamespaceInComposite = "otherNamespaceInComposite";
    private static final String someOtherNamespace = "someOtherNamespace";

    @Mock
    CompositeNamespaceService compositeNamespaceService;

    @Mock
    SecurityContext securityContext;

    @Mock
    JWTCallerPrincipal principal;

    @InjectMocks
    NamespaceValidator namespaceValidator;

    @BeforeEach
    void setUp() {
        namespaceValidator.namespaceIsolationEnabled = true;
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void isNamespaceFromPathValid() {
        Set<String> namespaces = Set.of(defaultNamespace, otherNamespaceInComposite);
        CompositeStructure defaultCompositeStructure = new CompositeStructure(defaultBaseLine, namespaces);

        when(compositeNamespaceService.getCompositeStructure(defaultBaseLine)).thenReturn(Optional.of(defaultCompositeStructure));

        when(compositeNamespaceService.getBaselineByNamespace(defaultNamespace)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace(otherNamespaceInComposite)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace(someOtherNamespace)).thenReturn(Optional.empty());

        assertTrue(namespaceValidator.isNamespaceFromPathValid(defaultNamespace, defaultNamespace));
        assertTrue(namespaceValidator.isNamespaceFromPathValid(someOtherNamespace, someOtherNamespace));
        assertFalse(namespaceValidator.isNamespaceFromPathValid(someOtherNamespace, "notEqualSomeOtherNamespace"));
        assertFalse(namespaceValidator.isNamespaceFromPathValid(defaultNamespace, someOtherNamespace));
        assertFalse(namespaceValidator.isNamespaceFromPathValid(otherNamespaceInComposite, someOtherNamespace));
        assertTrue(namespaceValidator.isNamespaceFromPathValid(otherNamespaceInComposite, defaultNamespace));
    }

    @Test
    void isNamespaceFromClassifierValid() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(compositeNamespaceService.getBaselineByNamespace(otherNamespaceInComposite)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace(someOtherNamespace)).thenReturn(Optional.empty());

        try (var jwtMock = mockStatic(JwtUtils.class)) {
            jwtMock.when(() -> JwtUtils.getNamespace(securityContext)).thenReturn(defaultNamespace);
            assertTrue(namespaceValidator.isNamespaceFromClassifierValid(securityContext, Collections.singletonMap("namespace", defaultNamespace)));
            assertTrue(namespaceValidator.isNamespaceFromClassifierValid(securityContext, Collections.singletonMap("namespace", otherNamespaceInComposite)));
            assertFalse(namespaceValidator.isNamespaceFromClassifierValid(securityContext, Collections.singletonMap("namespace", someOtherNamespace)));
        }
    }

    @Test
    void isNamespaceFromClassifierValid_isUserHasClusterOperatorRole() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(securityContext.isUserInRole(Constants.CLUSTER_OPERATOR)).thenReturn(true);

        assertTrue(namespaceValidator.isNamespaceFromClassifierValid(securityContext, Collections.singletonMap("namespace", someOtherNamespace)));

        verify(compositeNamespaceService, times(0)).getBaselineByNamespace(any());
        verify(compositeNamespaceService, times(0)).getCompositeStructure(any());
    }
}
