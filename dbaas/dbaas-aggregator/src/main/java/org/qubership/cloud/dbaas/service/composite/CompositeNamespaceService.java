package org.qubership.cloud.dbaas.service.composite;

import org.qubership.cloud.dbaas.dto.composite.CompositeStructureDto;
import org.qubership.cloud.dbaas.entity.pg.composite.CompositeNamespace;
import org.qubership.cloud.dbaas.entity.pg.composite.CompositeStructure;
import org.qubership.cloud.dbaas.repositories.dbaas.CompositeNamespaceDbaasRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@ApplicationScoped
public class CompositeNamespaceService {

    private CompositeNamespaceDbaasRepository compositeNamespaceDbaasRepository;

    public CompositeNamespaceService(CompositeNamespaceDbaasRepository compositeNamespaceDbaasRepository) {
        this.compositeNamespaceDbaasRepository = compositeNamespaceDbaasRepository;
    }

    @Transactional
    public void saveOrUpdateCompositeStructure(CompositeStructureDto compositeRequest) {
        deleteCompositeStructure(compositeRequest.getId());
        compositeNamespaceDbaasRepository.flush(); // need to flush because jpa first tries to save data without deleting it
        compositeRequest.getNamespaces().add(compositeRequest.getId());
        List<CompositeNamespace> compositeNamespaces = compositeRequest.getNamespaces().stream()
                .map(ns -> buildCompositeNamespace(compositeRequest, ns))
                .toList();
        compositeNamespaceDbaasRepository.saveAll(compositeNamespaces);
    }

    @NotNull
    private CompositeNamespace buildCompositeNamespace(CompositeStructureDto compositeRequest, String ns) {
        CompositeNamespace compositeNamespace = new CompositeNamespace(compositeRequest.getId(), ns);
        compositeNamespace.setId(UUID.randomUUID());
        return compositeNamespace;
    }


    public Optional<CompositeStructure> getCompositeStructure(String baseline) {
        List<CompositeNamespace> compositeNamespaces = compositeNamespaceDbaasRepository.findByBaseline(baseline);
        if (compositeNamespaces.isEmpty()) {
            return Optional.empty();
        }
        CompositeStructure compositeStructure = new CompositeStructure(baseline, compositeNamespaces.stream()
                .map(CompositeNamespace::getNamespace).collect(Collectors.toSet()));
        return Optional.of(compositeStructure);
    }


    public List<CompositeStructure> getAllCompositeStructures() {
        List<CompositeNamespace> compositeNamespaces = compositeNamespaceDbaasRepository.findAll();
        return compositeNamespaces
                .parallelStream()
                .collect(Collectors.groupingBy(CompositeNamespace::getBaseline,
                        Collectors.mapping(CompositeNamespace::getNamespace, Collectors.toSet())))
                .entrySet()
                .stream()
                .map(entry -> new CompositeStructure(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Transactional
    public void deleteCompositeStructure(String baseline) {
        log.info("Deleting composite structure by baseline {}", baseline);
        compositeNamespaceDbaasRepository.deleteByBaseline(baseline);
    }

    @Transactional
    public void deleteNamespace(String namespace) {
        if (isBaseline(namespace)) {
            deleteCompositeStructure(namespace);
        } else {
            log.info("Deleting composite structure by namespace {}", namespace);
            compositeNamespaceDbaasRepository.deleteByNamespace(namespace);
        }
    }

    public boolean isBaseline(String namespace) {
        return !compositeNamespaceDbaasRepository.findByBaseline(namespace).isEmpty();
    }

    public boolean isNamespaceInComposite(String namespace) {
        return getBaselineByNamespace(namespace).isPresent();
    }

    public Optional<String> getBaselineByNamespace(String namespace) {
        return compositeNamespaceDbaasRepository.findBaselineByNamespace(namespace)
                .map(CompositeNamespace::getBaseline);
    }
}
