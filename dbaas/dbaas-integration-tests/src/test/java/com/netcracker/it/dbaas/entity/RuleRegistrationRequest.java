package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RuleRegistrationRequest {
    private Long order;
    private String type;
    private RuleBody rule;
}
