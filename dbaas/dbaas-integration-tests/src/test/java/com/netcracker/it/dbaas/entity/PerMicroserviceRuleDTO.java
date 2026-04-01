package com.netcracker.it.dbaas.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PerMicroserviceRuleDTO {

    private UUID id;
    private String namespace;
    private String microservice;
    private List<RuleOnMicroservice> rules;
    private String type;
    private Date createDate;
    private Date updateDate;
    private Integer generation;
}
