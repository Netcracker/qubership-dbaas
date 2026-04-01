package com.netcracker.it.dbaas.entity.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RolesRegistration implements DeclarativeConfig {
    @JsonProperty("services")
    List<ServiceRole> services;

    @JsonProperty("policy")
    List<PolicyRole> policy;

    @JsonProperty("disableGlobalPermissions")
    Boolean disableGlobalPermissions;

    public DeclarativePayload asPayload(String namespace, String serviceName) {
        return new DeclarativePayload(serviceName, namespace, serviceName + "-dbPolicy", "DbPolicy", this);
    }

    public static class DeclarativeRoleConfigBuilder {
        private final RolesRegistration rolesRegistration;

        public DeclarativeRoleConfigBuilder() {
            rolesRegistration = new RolesRegistration();
            rolesRegistration.services = null;
            rolesRegistration.policy = null;
            rolesRegistration.disableGlobalPermissions = false;
        }

        public DeclarativeRoleConfigBuilder service(String serviceName, String... roles) {
            ServiceRole serviceRole = new ServiceRole();
            serviceRole.setName(serviceName);
            serviceRole.setRoles(List.of(roles));
            if (rolesRegistration.services == null) {
                rolesRegistration.services = new ArrayList<>();
            }
            rolesRegistration.services.add(serviceRole);
            return this;
        }

        public DeclarativeRoleConfigBuilder disableGlobalPermissions() {
            rolesRegistration.disableGlobalPermissions = true;
            return this;
        }

        public RolesRegistration build() {
            return rolesRegistration;
        }
    }
}
