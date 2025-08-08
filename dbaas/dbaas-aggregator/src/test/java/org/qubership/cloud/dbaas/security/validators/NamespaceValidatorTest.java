package org.qubership.cloud.dbaas.security.validators;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.ws.rs.core.SecurityContext;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import static org.mockito.Mockito.*;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.cloud.dbaas.entity.pg.composite.CompositeStructure;
import org.qubership.cloud.dbaas.service.composite.CompositeNamespaceService;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void checkNamespaceIsolation() {
        namespaceValidator.thisBaseline = defaultBaseLine;

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
        namespaceValidator.thisBaseline = defaultBaseLine;

        Set<String> namespaces = Set.of(defaultNamespace, otherNamespaceInComposite);
        CompositeStructure defaultCompositeStructure = new CompositeStructure(defaultBaseLine, namespaces);

        when(compositeNamespaceService.getCompositeStructure(defaultBaseLine)).thenReturn(Optional.of(defaultCompositeStructure));
        when(compositeNamespaceService.getBaselineByNamespace(otherNamespaceInComposite)).thenReturn(Optional.of(defaultBaseLine));
        when(compositeNamespaceService.getBaselineByNamespace("someOtherNamespace")).thenReturn(Optional.empty());

        JwtClaims claims = new JwtClaims();
        claims.setClaim("kubernetes.io", Collections.singletonMap("namespace", defaultNamespace));

        DefaultJWTCallerPrincipal principal = new DefaultJWTCallerPrincipal(claims);

        when(securityContext.getUserPrincipal()).thenReturn(principal);

        assertTrue(namespaceValidator.checkNamespaceFromClassifier(Collections.singletonMap("namespace", defaultNamespace)));
        assertTrue(namespaceValidator.checkNamespaceFromClassifier(Collections.singletonMap("namespace", otherNamespaceInComposite)));
        assertFalse(namespaceValidator.checkNamespaceFromClassifier(Collections.singletonMap("namespace", "someOtherNamespace")));
    }
}
