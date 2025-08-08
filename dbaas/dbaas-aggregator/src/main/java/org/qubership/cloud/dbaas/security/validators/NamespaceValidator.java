package org.qubership.cloud.dbaas.security.validators;

import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.cloud.dbaas.entity.pg.composite.CompositeStructure;
import org.qubership.cloud.dbaas.service.composite.CompositeNamespaceService;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

@RequestScoped
@Slf4j
public class NamespaceValidator {
    @ConfigProperty(name = "dbaas.security.namespace-isolation-enabled")
    boolean namespaceIsolationEnabled;

    @Inject
    CompositeNamespaceService compositeNamespaceService;

    @Inject
    SecurityContext securityContext;

    public boolean checkNamespaceIsolation(String namespaceFromPath, String namespaceFromJwt) {
        if (!namespaceIsolationEnabled) {
            return true;
        }

        if (namespaceFromPath.equals(namespaceFromJwt)) {
            return true;
        } else {
            return inSameCompositeStructure(namespaceFromPath, namespaceFromJwt);
        }
    }

    public boolean checkNamespaceFromClassifier(Map<String, Object> classifier) {
        Principal defaultPrincipal = securityContext.getUserPrincipal();

        if (!(defaultPrincipal instanceof DefaultJWTCallerPrincipal principal)) {
            return true;
        }
        String namespaceFromClassifier = (String) classifier.get("namespace");
        if (namespaceFromClassifier == null) {
            return false;
        }

        Map<String, Object> kubernetesClaims = principal.getClaim("kubernetes.io");
        if (kubernetesClaims == null) {
            return false;
        }

        JsonString namespaceFromJwt = (JsonString) kubernetesClaims.get("namespace");
        if (namespaceFromJwt == null) {
            return false;
        }

        return namespaceFromClassifier.equals(namespaceFromJwt.getString()) || inSameCompositeStructure(namespaceFromClassifier, namespaceFromJwt.getString());
    }

    private boolean inSameCompositeStructure(String namespace0, String namespace1) {
        Optional<String> baseLine = compositeNamespaceService.getBaselineByNamespace(namespace0);

        if (baseLine.isEmpty()) {
            return false;
        }

        Optional<CompositeStructure> compositeStructure = compositeNamespaceService.getCompositeStructure(baseLine.get());

        if (compositeStructure.isEmpty()) {
            return false;
        }

        return compositeStructure.get().getNamespaces().contains(namespace1);
    }
}
