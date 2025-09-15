package com.netcracker.cloud.dbaas.dto.role;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

public class ServiceAccountWithRoles {
    @Setter
    @Getter
    private String name;

    @Setter
    @Getter
    private Set<String> roles;

    public ServiceAccountWithRoles(String name, Set<String> roles) {
        this.name = name;
        this.roles = roles;
    }
}
