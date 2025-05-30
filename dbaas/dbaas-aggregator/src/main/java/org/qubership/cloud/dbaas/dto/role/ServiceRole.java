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
public class ServiceRole {
    String name;
    List<String> roles;

    public ServiceRole(ServiceRole serviceRole) {
        this.name = serviceRole.getName();
        this.roles = serviceRole.getRoles() == null ? null : serviceRole.getRoles().stream().map(String::new).collect(Collectors.toList());
    }
}
