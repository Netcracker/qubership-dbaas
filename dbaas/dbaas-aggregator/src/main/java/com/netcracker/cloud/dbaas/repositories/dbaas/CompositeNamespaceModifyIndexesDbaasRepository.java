package com.netcracker.cloud.dbaas.repositories.dbaas;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeProperties;

import java.util.Optional;

public interface CompositeNamespaceModifyIndexesDbaasRepository {
    Optional<CompositeProperties> findByBaselineName(String baselineName);

    void save(CompositeProperties compositeNamespacesModifyIndex);

}
