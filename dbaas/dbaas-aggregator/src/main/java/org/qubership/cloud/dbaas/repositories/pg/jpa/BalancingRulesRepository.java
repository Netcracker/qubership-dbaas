package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.dto.RuleType;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
@Transactional
public class BalancingRulesRepository implements PanacheRepositoryBase<PerNamespaceRule, String> {
    public PerNamespaceRule findByName(String name) {
        return find("name", name).firstResult();
    }

    public PerNamespaceRule findByOrderAndDatabaseTypeAndRuleType(Long order, String databaseType, RuleType ruleType) {
        return find("order = ?1 and databaseType = ?2 and ruleType = ?3", order, databaseType, ruleType).firstResult();
    }

    public List<PerNamespaceRule> findByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public List<PerNamespaceRule> findByNamespaceAndRuleType(String namespace, RuleType ruleType) {
        return list("namespace = ?1 and ruleType = ?2", namespace, ruleType);
    }

    public PerNamespaceRule findByNamespaceAndDatabaseTypeAndRuleType(String namespace, String dbType, RuleType ruleType) {
        return find("namespace = ?1 and databaseType = ?2 and ruleType = ?3", namespace, dbType, ruleType).firstResult();
    }

    public List<PerNamespaceRule> findByRuleType(RuleType ruleType) {
        return list("ruleType", ruleType);
    }

    public void deleteByNamespaceAndRuleType(String namespace, RuleType ruleType) {
        delete("namespace = ?1 and ruleType = ?2", namespace, ruleType);
    }

    public void deleteByNamespaceAndDatabaseTypeAndRuleType(String namespace, String type, RuleType ruleType) {
        delete("namespace = ?1 and databaseType = ?2 and ruleType = ?3", namespace, type, ruleType);
    }
}
