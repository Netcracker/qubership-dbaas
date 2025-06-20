package org.qubership.cloud.dbaas.entity.pg.rule;

import org.qubership.cloud.dbaas.dto.RuleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "PerNamespaceRule")
@Table(name = "per_namespace_rule")
public class PerNamespaceRule {
    @Id
    @NonNull
    private String name;

    @NonNull
    @Column(name = "ordering")
    private Long order;

    @NonNull
    @Column(name = "database_type")
    private String databaseType;

    @NonNull
    private String namespace;

    @NonNull
    @Column(name = "physical_database_identifier")
    private String physicalDatabaseIdentifier;

    @NonNull
    @Column(name = "rule_type")
    @Enumerated(EnumType.STRING)
    private RuleType ruleType;

    public PerNamespaceRule(PerNamespaceRule rule) {
        this.name = rule.getName();
        this.order = rule.getOrder();
        this.databaseType = rule.getDatabaseType();
        this.namespace = rule.getNamespace();
        this.physicalDatabaseIdentifier = rule.getPhysicalDatabaseIdentifier();
        this.ruleType = rule.getRuleType();
    }
}
