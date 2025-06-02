package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.dto.RuleType;
import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;

import java.util.List;
import java.util.Optional;

public interface BalancingRulesDbaasRepository {
    PerNamespaceRule findByName(String name);

    PerNamespaceRule findByOrderAndDatabaseType(Long order, String databaseType);

    List<PerNamespaceRule> findByNamespace(String namespace);

    List<PerNamespaceRule> findAllRulesByNamespace(String namespace);

    List<PerNamespaceRule> findByNamespaceAndRuleType(String namespace, RuleType ruleType);

    List<PerNamespaceRule> findByRuleType(RuleType ruleType);

    PerNamespaceRule findPerNamespaceRuleByNamespaceAndDatabaseTypeAndRuleType(String namespace, String dbType, RuleType ruleType);

    PerNamespaceRule save(PerNamespaceRule storedRule);

    List<PerNamespaceRule> saveAllNamespaceRules(Iterable<PerNamespaceRule> storedRules);

    void deleteAll(List<PerNamespaceRule> perNamespaceRule);

    List<PerMicroserviceRule> findPerMicroserviceByNamespace(String namespace);

    List<PerMicroserviceRule> findPerMicroserviceByNamespaceWithMaxGeneration(String namespace);

    PerMicroserviceRule save(PerMicroserviceRule storedRule);

    List<PerMicroserviceRule> saveAll(Iterable<PerMicroserviceRule> storedRules);

    Optional<PerMicroserviceRule> findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(String namespace, String microservice, String type);

    void deleteAllPerMicroserviceRules(List<PerMicroserviceRule> perMicroserviceRules);

    void deleteByNamespaceAndRuleType(String namespaces, RuleType ruleType);

    void deleteByNamespaceAndDbTypeAndRuleType(String namespace, String type, RuleType ruleType);
}
