package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OnMicroserviceRuleRequest {
    @NonNull
    private String type;

    private List<RuleOnMicroservice> rules;

    private List<String> microservices;
}
