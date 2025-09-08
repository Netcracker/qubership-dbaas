package com.netcracker.cloud.dbaas.security;

import io.quarkus.security.credential.Credential;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.netcracker.cloud.dbaas.Constants;

import java.util.Set;

@ApplicationScoped
public class ServiceAccountRolesAugmentor implements SecurityIdentityAugmentor {
    @Inject
    ServiceAccountRolesManager rolesManager;

    public ServiceAccountRolesAugmentor(ServiceAccountRolesManager rolesManager) {
        this.rolesManager = rolesManager;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }

        // skip if basic auth
        for (Credential cred : identity.getCredentials()) {
            if (cred instanceof PasswordCredential) {
                return Uni.createFrom().item(identity);
            }
        }

        String principal = identity.getPrincipal().getName();
        String serviceName = principal.substring(principal.lastIndexOf(':') + 1);
        Set<String> roles = rolesManager.getRolesByServiceAccountName(serviceName);

        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);
        if (roles != null && !roles.isEmpty()) {
            builder.addRoles(roles);
        } else {
            builder.addRole(Constants.DB_CLIENT);
        }

        return Uni.createFrom().item(builder.build());
    }
}
