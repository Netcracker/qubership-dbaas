package org.qubership.cloud.dbaas.entity.pg.rule;

import org.qubership.cloud.dbaas.converter.ListRuleOnMicroserviceConverter;
import org.qubership.cloud.dbaas.dto.RuleOnMicroservice;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;


@Data
@NoArgsConstructor
@RequiredArgsConstructor
@Entity(name = "PerMicroserviceRule")
@Table(name = "per_microservice_rule")
public class PerMicroserviceRule{

    public static final String LABEL = "label";

    public PerMicroserviceRule(List<RuleOnMicroservice> rule, String type, String namespace, String microservice) {
        this.namespace = namespace;
        this.microservice = microservice;
        this.rules = rule;
        this.type = type;
        this.createDate = new Date();
        this.updateDate = new Date();
        this.generation = 0;
    }

    public PerMicroserviceRule(PerMicroserviceRule perMicroserviceRule) {
        this.namespace = perMicroserviceRule.getNamespace();
        this.microservice = perMicroserviceRule.getMicroservice();
        this.rules = perMicroserviceRule.getRules().stream().map(RuleOnMicroservice::new).collect(Collectors.toList());
        this.type = perMicroserviceRule.getType();
        this.createDate = new Date();
        this.updateDate = new Date();
        this.generation = -1;
    }

    @Id
    @GeneratedValue
    private UUID id;

    @NonNull
    private String namespace;

    @NonNull
    private String microservice;

    @NonNull
    @Convert(converter = ListRuleOnMicroserviceConverter.class)
    private List<RuleOnMicroservice> rules;

    @NonNull
    @Column(name = "database_type")
    private String type;

    @NonNull
    @Column(name = "create_date")
    private Date createDate;

    @Column(name = "update_date")
    @NonNull
    private Date updateDate;

    @Column(name = "generation")
    @NonNull
    private Integer generation;
}
