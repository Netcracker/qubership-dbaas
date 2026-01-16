package com.netcracker.cloud.dbaas.repositories.dbaas;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeNamespaceModifyIndex;

import java.util.Optional;

public interface CompositeNamespaceModifyIndexesDbaasRepository {
    Optional<CompositeNamespaceModifyIndex> findByBaselineName(String baselineName);

    void save(CompositeNamespaceModifyIndex compositeNamespacesModifyIndex);

}
