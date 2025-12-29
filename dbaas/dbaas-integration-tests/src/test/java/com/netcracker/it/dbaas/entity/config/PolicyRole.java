package com.netcracker.it.dbaas.entity.config;

import lombok.Data;

import java.util.List;

@Data
public class PolicyRole {
    String type;
    String defaultRole;
    List<String> additionalRole;
}
