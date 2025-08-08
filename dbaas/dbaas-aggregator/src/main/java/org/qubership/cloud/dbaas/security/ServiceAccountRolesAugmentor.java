package org.qubership.cloud.dbaas.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.cloud.dbaas.Constants;

import java.util.HashSet;
import java.util.List;

@ApplicationScoped
public class ServiceAccountRolesAugmentor implements SecurityIdentityAugmentor {
    @Inject
    ServiceAccountRoles secretReader;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        String principal = identity.getPrincipal().getName();
        String serviceName = principal.substring(principal.lastIndexOf(':') + 1);
        List<String> roles = secretReader.getRolesByServiceAccountName(serviceName);

        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);
        if (roles != null && !roles.isEmpty()) {
            builder.addRoles(new HashSet<>(roles));
        } else {
            builder.addRole(Constants.DB_CLIENT);
        }

        return Uni.createFrom().item(builder.build());
    }
}
