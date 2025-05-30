package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.BgNamespace;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Optional;

@ApplicationScoped
@Transactional
public class BgNamespaceRepository implements PanacheRepositoryBase<BgNamespace, String> {
    public Optional<BgNamespace> findBgNamespaceByNamespace(String namespace) {
        return find("namespace", namespace).firstResultOptional();
    }
}
