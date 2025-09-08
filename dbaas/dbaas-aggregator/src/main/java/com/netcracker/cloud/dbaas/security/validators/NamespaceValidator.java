package com.netcracker.cloud.dbaas.security.validators;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeStructure;
import com.netcracker.cloud.dbaas.service.composite.CompositeNamespaceService;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class NamespaceValidator {
    @ConfigProperty(name = "dbaas.security.namespace-isolation-enabled")
    boolean namespaceIsolationEnabled;

    @Inject
    CompositeNamespaceService compositeNamespaceService;

    public boolean checkNamespaceIsolation(String namespaceFromPath, String namespaceFromJwt) {
        if (!namespaceIsolationEnabled) {
            return true;
        }
        return checkNamespacesEqual(namespaceFromPath, namespaceFromJwt);
    }

    public boolean checkNamespaceFromClassifier(Map<String, Object> classifier, String namespaceFromJwt) {
        String namespaceFromClassifier = (String) classifier.get("namespace");
        if (namespaceFromClassifier == null) {
            return false;
        }
        return checkNamespacesEqual(namespaceFromClassifier, namespaceFromJwt);
    }

    private boolean checkNamespacesEqual(String namespace0, String namespace1) {
        if (namespace0.equals(namespace1)) {
            return true;
        } else {
            return inSameCompositeStructure(namespace0, namespace1);
        }
    }

    private boolean inSameCompositeStructure(String namespace0, String namespace1) {
        Optional<String> baseLine = compositeNamespaceService.getBaselineByNamespace(namespace0);

        if (baseLine.isEmpty()) {
            return false;
        }

        if (baseLine.get().equals(namespace1)) {
            return true;
        }

        Optional<CompositeStructure> compositeStructure = compositeNamespaceService.getCompositeStructure(baseLine.get());

        return compositeStructure.map(structure -> structure.getNamespaces().contains(namespace1))
                .orElse(false);

    }
}
