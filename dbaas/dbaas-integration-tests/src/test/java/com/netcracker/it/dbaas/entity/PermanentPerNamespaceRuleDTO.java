package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PermanentPerNamespaceRuleDTO {

    private String dbType;
    private String physicalDatabaseId;
    private List<String> namespaces;
}
