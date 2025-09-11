package com.netcracker.cloud.dbaas.security.validators;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.ws.rs.core.SecurityContext;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeStructure;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamespaceValidatorTest {
    private static final String defaultBaseLine = "default";
    private static final String defaultNamespace = "default";
    private static final String otherNamespaceInComposite = "otherNamespaceInComposite";

    @Mock
    CompositeNamespaceService compositeNamespaceService;

    @Mock
    SecurityContext securityContext;

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
    void checkNamespaceIsolation() {
        Set<String> namespaces = Set.of(defaultNamespace, otherNamespaceInComposite);
        CompositeStructure defaultCompositeStructure = new CompositeStructure(defaultBaseLine, namespaces);

        when(compositeNamespaceService.getCompositeStructure(defaultBaseLine)).thenReturn(Optional.of(defaultCompositeStructure));

        when(compositeNamespaceService.getBaselineByNamespace(defaultNamespace)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace(otherNamespaceInComposite)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace("someOtherNamespace")).thenReturn(Optional.empty());

        assertTrue(namespaceValidator.checkNamespaceIsolation(defaultNamespace, defaultNamespace));
        assertTrue(namespaceValidator.checkNamespaceIsolation("someOtherNamespace", "someOtherNamespace"));
        assertFalse(namespaceValidator.checkNamespaceIsolation("someOtherNamespace", "notEqualSomeOtherNamespace"));
        assertFalse(namespaceValidator.checkNamespaceIsolation(defaultNamespace, "someOtherNamespace"));
        assertFalse(namespaceValidator.checkNamespaceIsolation(otherNamespaceInComposite, "someOtherNamespace"));
        assertTrue(namespaceValidator.checkNamespaceIsolation(otherNamespaceInComposite, defaultNamespace));
    }

    @Test
    void checkNamespaceFromClassifier() {
        Set<String> namespaces = Set.of(defaultNamespace, otherNamespaceInComposite);

        when(compositeNamespaceService.getBaselineByNamespace(otherNamespaceInComposite)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace("someOtherNamespace")).thenReturn(Optional.empty());

        assertTrue(namespaceValidator.checkNamespaceFromClassifier(Collections.singletonMap("namespace", defaultNamespace), defaultNamespace));
        assertTrue(namespaceValidator.checkNamespaceFromClassifier(Collections.singletonMap("namespace", otherNamespaceInComposite), defaultNamespace));
        assertFalse(namespaceValidator.checkNamespaceFromClassifier(Collections.singletonMap("namespace", "someOtherNamespace"), defaultNamespace));
    }
}
