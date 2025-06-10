package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.dto.RuleType;
import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import org.qubership.cloud.dbaas.entity.pg.rule.PerNamespaceRule;
import org.qubership.cloud.dbaas.repositories.dbaas.BalancingRulesDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BalanceRulesRepositoryPerMicroservice;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BalancingRulesRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@AllArgsConstructor
@ApplicationScoped
@Transactional
public class BalancingRulesDbaasRepositoryImpl implements BalancingRulesDbaasRepository {

    private BalancingRulesRepository rulesRepository;
    private BalanceRulesRepositoryPerMicroservice rulesRepositoryPerMicroservice;

    public PerNamespaceRule findByOrderAndDatabaseType(Long requestOrder, String type) {
        return rulesRepository.findByOrderAndDatabaseTypeAndRuleType(requestOrder, type, RuleType.NAMESPACE);
    }

    public PerNamespaceRule findByName(String ruleName) {
        return rulesRepository.findByName(ruleName);
    }

    public PerNamespaceRule save(PerNamespaceRule storedRule) {
        rulesRepository.persist(storedRule);
        return storedRule;
    }

    public List<PerNamespaceRule> saveAllNamespaceRules(Iterable<PerNamespaceRule> storedRules) {
        rulesRepository.persist(storedRules);
        return StreamSupport.stream(storedRules.spliterator(), true).toList();
    }

    public List<PerNamespaceRule> findByNamespace(String namespace) {
        return rulesRepository.findByNamespaceAndRuleType(namespace, RuleType.NAMESPACE);
    }

    public List<PerNamespaceRule> findAllRulesByNamespace(String namespace) {
        return rulesRepository.findByNamespace(namespace);
    }

    public List<PerNamespaceRule> findByNamespaceAndRuleType(String namespace, RuleType ruleType) {
        return rulesRepository.findByNamespaceAndRuleType(namespace, ruleType);
    }

    public List<PerNamespaceRule> findByRuleType(RuleType ruleType) {
        return rulesRepository.findByRuleType(ruleType);
    }

    public PerNamespaceRule findPerNamespaceRuleByNamespaceAndDatabaseTypeAndRuleType(String namespace, String dbType, RuleType ruleType) {
        return rulesRepository.findByNamespaceAndDatabaseTypeAndRuleType(namespace, dbType, ruleType);
    }

    public void deleteAll(List<PerNamespaceRule> perNamespaceRule) {
        perNamespaceRule.forEach(rulesRepository::delete);
    }


    public List<PerMicroserviceRule> findPerMicroserviceByNamespace(String namespace) {
        return rulesRepositoryPerMicroservice.findByNamespace(namespace);
    }

    public List<PerMicroserviceRule> findPerMicroserviceByNamespaceWithMaxGeneration(String namespace) {
        return rulesRepositoryPerMicroservice.findAllByNamespaceWithMaxGeneration(namespace);
    }

    public PerMicroserviceRule save(PerMicroserviceRule storedRule) {
        rulesRepositoryPerMicroservice.persist(storedRule);
        return storedRule;
    }

    public List<PerMicroserviceRule> saveAll(Iterable<PerMicroserviceRule> storedRules) {
        rulesRepositoryPerMicroservice.persist(storedRules);
        return StreamSupport.stream(storedRules.spliterator(), true).toList();
    }

    public Optional<PerMicroserviceRule> findPerMicroserviceByNamespaceAndMicroserviceAndTypeWithMaxGeneration(String namespace, String microservice, String type) {
        return rulesRepositoryPerMicroservice.findByNamespaceAndMicroserviceAndTypeWithMaxGeneration(namespace, microservice, type);
    }

    public void deleteAllPerMicroserviceRules(List<PerMicroserviceRule> perMicroserviceRules) {
        perMicroserviceRules.forEach(rulesRepositoryPerMicroservice::delete);
    }

    public void deleteByNamespaceAndRuleType(String namespace, RuleType ruleType) {
        rulesRepository.deleteByNamespaceAndRuleType(namespace, ruleType);
    }

    public void deleteByNamespaceAndDbTypeAndRuleType(String namespace, String dbType, RuleType ruleType) {
        rulesRepository.deleteByNamespaceAndDatabaseTypeAndRuleType(namespace, dbType, ruleType);
    }


}
