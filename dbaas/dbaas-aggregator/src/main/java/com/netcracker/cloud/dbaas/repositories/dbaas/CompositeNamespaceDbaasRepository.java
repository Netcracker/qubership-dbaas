package com.netcracker.cloud.dbaas.repositories.dbaas;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeNamespace;

import java.util.List;
import java.util.Optional;

public interface CompositeNamespaceDbaasRepository {
    void deleteByBaseline(String baseline);

    void deleteByNamespace(String namespace);

    void saveAll(List<CompositeNamespace> compositeNamespaces);

    List<CompositeNamespace> findByBaseline(String baseline);

    List<CompositeNamespace> findAll();

    Optional<CompositeNamespace> findBaselineByNamespace(String namespace);

    void flush();
}
