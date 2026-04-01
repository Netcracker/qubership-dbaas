package com.netcracker.it.dbaas.entity.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;


@EqualsAndHashCode(callSuper = true)
@Data
public class SecurityRuleConfigurationRequest extends RolesRegistration {
    @JsonProperty("kind")
    String kind;

    private String apiVersion;
}
