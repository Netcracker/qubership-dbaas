package org.qubership.cloud.dbaas.dao.jpa;

import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import org.qubership.cloud.dbaas.repositories.dbaas.BackupsDbaasRepository;
import org.qubership.cloud.dbaas.repositories.pg.jpa.BackupsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@AllArgsConstructor
@ApplicationScoped
@Transactional
public class BackupsDbaasRepositoryImpl implements BackupsDbaasRepository {

    private BackupsRepository backupsRepository;

    @Override
    public List<NamespaceBackup> findByNamespace(String namespace) {
        return backupsRepository.findByNamespace(namespace);
    }

    @Override
    public long countByNamespaces(Set<String> namespaces) {
        return backupsRepository.count("namespace in ?1", namespaces);
    }

    @Override
    public List<UUID> findAllNotDeletableBackupIdsByNamespaces(Set<String> namespaces) {
        return backupsRepository.findAllNotDeletableBackupIdsByNamespaces(namespaces);
    }

    @Override
    public List<NamespaceBackup> findByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit) {
        return backupsRepository.findByNamespacesWithOffsetBasedPagination(namespaces, offset, limit);
    }

    @Override
    public List<NamespaceBackup> findDeletableByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit) {
        return backupsRepository.findDeletableByNamespacesWithOffsetBasedPagination(namespaces, offset, limit);
    }

    @Override
    public Optional<NamespaceBackup> findById(UUID backupId) {
        return backupsRepository.findByIdOptional(backupId);
    }

    @Override
    public NamespaceBackup save(NamespaceBackup backup) {
        if (backup.getId() == null) {
            backupsRepository.persistAndFlush(backup);
        } else {
            EntityManager entityManager = backupsRepository.getEntityManager();
            entityManager.merge(backup);
            entityManager.flush();
        }
        return backup;
    }

    @Override
    public void delete(NamespaceBackup backupToDelete) {
        backupsRepository.delete(backupToDelete);
    }

    @Override
    public void detach(NamespaceBackup backup) {
        backupsRepository.getEntityManager().detach(backup);
    }

    @Override
    public EntityManager getEntityManager() {
        return backupsRepository.getEntityManager();
    }
}
