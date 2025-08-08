package org.qubership.cloud.dbaas.dto.role;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class ServiceAccountWithRoles {
    @Setter
    @Getter
    private String name;

    @Setter
    @Getter
    private List<String> roles;

    public ServiceAccountWithRoles(String name, List<String> roles) {
        this.name = name;
        this.roles = roles;
    }
}
