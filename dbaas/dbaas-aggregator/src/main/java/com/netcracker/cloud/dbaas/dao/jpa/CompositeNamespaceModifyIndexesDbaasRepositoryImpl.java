package com.netcracker.cloud.dbaas.dao.jpa;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeProperties;
import com.netcracker.cloud.dbaas.repositories.dbaas.CompositeNamespaceModifyIndexesDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.CompositeNamespaceModifyIndexesRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;

import java.util.Optional;

@AllArgsConstructor
@ApplicationScoped
public class CompositeNamespaceModifyIndexesDbaasRepositoryImpl implements CompositeNamespaceModifyIndexesDbaasRepository {

    private CompositeNamespaceModifyIndexesRepository compositeNamespaceModifyIndexesRepository;

    @Override
    public Optional<CompositeProperties> findByBaselineName(String baselineName) {
        return compositeNamespaceModifyIndexesRepository.findByBaseline(baselineName);
    }

    @Transactional
    @Override
    public void save(CompositeProperties compositeNamespacesModifyIndex) {
        compositeNamespaceModifyIndexesRepository.persist(compositeNamespacesModifyIndex);
    }
}
