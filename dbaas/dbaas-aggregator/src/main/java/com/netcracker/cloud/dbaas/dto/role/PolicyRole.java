package org.qubership.cloud.dbaas.dto.role;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class PolicyRole {
    String type;
    String defaultRole;
    List<String> additionalRole;
    public PolicyRole(PolicyRole policyRole) {
        this.type = policyRole.getType();
        this.defaultRole = policyRole.getDefaultRole();
        this.additionalRole = policyRole.getAdditionalRole() == null ? null :  policyRole.getAdditionalRole().stream().map(String::new).collect(Collectors.toList());
    }
}
