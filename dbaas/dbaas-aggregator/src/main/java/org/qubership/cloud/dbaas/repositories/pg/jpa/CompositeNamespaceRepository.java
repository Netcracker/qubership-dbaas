package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.composite.CompositeNamespace;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CompositeNamespaceRepository implements PanacheRepositoryBase<CompositeNamespace, UUID> {

    public void deleteByBaseline(String baseline) {
        delete("baseline", baseline);
    }

    public void deleteByNamespace(String namespace) {
        delete("namespace", namespace);
    }

    public List<CompositeNamespace> findByBaseline(String baseline) {
        return list("baseline", baseline);
    }

    public Optional<CompositeNamespace> findByNamespace(String namespace) {
        return find("namespace", namespace).firstResultOptional();
    }
}
