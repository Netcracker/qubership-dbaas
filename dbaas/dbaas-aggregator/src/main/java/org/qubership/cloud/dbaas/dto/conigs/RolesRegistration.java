package org.qubership.cloud.dbaas.dto.conigs;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.qubership.cloud.dbaas.dto.role.PolicyRole;
import org.qubership.cloud.dbaas.dto.role.ServiceRole;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.util.List;

@Data
@RegisterForReflection
public class RolesRegistration implements DeclarativeConfig {
    @JsonProperty("services")
    List<ServiceRole> services;

    @JsonProperty("policy")
    List<PolicyRole> policy;

    @JsonProperty("disableGlobalPermissions")
    Boolean disableGlobalPermissions;
}
