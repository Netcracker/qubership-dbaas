package org.qubership.cloud.dbaas.security;

import lombok.Data;

import java.util.List;

@Data
public class UserConfig {
    private List<String> roles;
    private transient String password;
}
