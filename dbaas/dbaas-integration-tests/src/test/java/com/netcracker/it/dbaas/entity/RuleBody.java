package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class RuleBody {

    public enum RuleType {
        perNamespace
    }

    private RuleType type;
    private Map<String, Object> config;
}
