package org.qubership.cloud.dbaas.repositories.dbaas;

import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BackupsDbaasRepository {

    List<NamespaceBackup> findByNamespace(String namespace);

    long countByNamespaces(Set<String> namespaces);

    List<UUID> findAllNotDeletableBackupIdsByNamespaces(Set<String> namespaces);

    List<NamespaceBackup> findByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit);

    List<NamespaceBackup> findDeletableByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit);

    Optional<NamespaceBackup> findById(UUID backupId);

    NamespaceBackup save(NamespaceBackup backup);

    void delete(NamespaceBackup backupToDelete);

    void detach(NamespaceBackup backup);

    EntityManager getEntityManager();
}
