package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class Mapping {
    private Map<String, String> namespaces;

    private Map<String, String> tenants;
}
