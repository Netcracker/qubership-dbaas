package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.rule.PerMicroserviceRule;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Transactional
public class BalanceRulesRepositoryPerMicroservice implements PanacheRepositoryBase<PerMicroserviceRule, String> {
    public List<PerMicroserviceRule> findByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    @SuppressWarnings("unchecked")
    public Optional<PerMicroserviceRule> findByNamespaceAndMicroserviceAndTypeWithMaxGeneration(String namespace, String microservice, String type) {
        return getEntityManager().createNativeQuery("""
                        select  * from per_microservice_rule p 
                        where p.namespace = ?1 and p.microservice = ?2 and p.database_type = ?3 
                        order by p.generation desc 
                        limit 1
                        """, PerMicroserviceRule.class)
                .setParameter(1, namespace)
                .setParameter(2, microservice)
                .setParameter(3, type)
                .getResultStream().findFirst();
    }

    @SuppressWarnings("unchecked")
    public List<PerMicroserviceRule> findAllByNamespaceWithMaxGeneration(String namespace) {
        return getEntityManager().createNativeQuery("""
                select distinct on (p.database_type, p.microservice) * from per_microservice_rule p 
                where p.namespace = ?1 
                order by p.database_type, p.microservice, p.generation desc
                """, PerMicroserviceRule.class).setParameter(1, namespace).getResultList();
    }
}