package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PermanentPerNamespaceRuleDeleteDTO {
    private String dbType;
    private List<String> namespaces;
}
