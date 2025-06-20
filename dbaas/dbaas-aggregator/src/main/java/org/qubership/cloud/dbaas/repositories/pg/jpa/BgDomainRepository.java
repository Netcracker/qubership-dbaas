package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.BgDomain;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class BgDomainRepository implements PanacheRepositoryBase<BgDomain, UUID> {

    public Optional<BgDomain> findByControllerNamespace(String namespace) {
        return find("controllerNamespace", namespace).firstResultOptional();
    };
}
