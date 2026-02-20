package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeProperties;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CompositeNamespaceModifyIndexesRepository implements PanacheRepositoryBase<CompositeProperties, UUID> {

    public Optional<CompositeProperties> findByBaseline(String baseline) {
        return find(
                "compositeNamespace.baseline",
                baseline
        ).firstResultOptional();
    }
}
