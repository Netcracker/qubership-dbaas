package com.netcracker.cloud.dbaas.dao.jpa;

import com.netcracker.cloud.dbaas.entity.pg.composite.CompositeNamespace;
import com.netcracker.cloud.dbaas.repositories.dbaas.CompositeNamespaceDbaasRepository;
import com.netcracker.cloud.dbaas.repositories.pg.jpa.CompositeNamespaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Optional;

@AllArgsConstructor
@ApplicationScoped
public class CompositeNamespaceDbaasRepositoryImpl implements CompositeNamespaceDbaasRepository {

    private CompositeNamespaceRepository compositeNamespaceRepository;

    @Transactional
    public void deleteByBaseline(String baseline) {
        compositeNamespaceRepository.deleteByBaseline(baseline);
    }

    @Override
    @Transactional
    public void deleteByNamespace(String namespace) {
        compositeNamespaceRepository.deleteByNamespace(namespace);
    }

    @Override
    @Transactional
    public void saveAll(List<CompositeNamespace> compositeNamespaces) {
        compositeNamespaceRepository.persist(compositeNamespaces);
    }

    @Override
    public List<CompositeNamespace> findByBaseline(String baseline) {
        return compositeNamespaceRepository.findByBaseline(baseline);
    }

    @Override
    public List<CompositeNamespace> findAll() {
        return compositeNamespaceRepository.findAll().list();
    }

    @Override
    public Optional<CompositeNamespace> findBaselineByNamespace(String namespace) {
        return compositeNamespaceRepository.findByNamespace(namespace);
    }

    @Override
    public void flush() {
        compositeNamespaceRepository.flush();
    }
}
