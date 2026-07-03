package com.netcracker.cloud.dbaas.security;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.Data;

import java.util.List;

@Data
public class UserConfig {
    private List<String> roles;
    private transient String password;
}
