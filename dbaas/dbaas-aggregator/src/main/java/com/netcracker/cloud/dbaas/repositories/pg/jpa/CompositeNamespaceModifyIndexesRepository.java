package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeNamespaceModifyIndex;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CompositeNamespaceModifyIndexesRepository implements PanacheRepositoryBase<CompositeNamespaceModifyIndex, UUID> {

    public Optional<CompositeNamespaceModifyIndex> findByBaseline(String baseline) {
        return find(
                "compositeNamespace.baseline",
                baseline
        ).firstResultOptional();
    }
}
