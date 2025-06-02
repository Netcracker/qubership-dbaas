package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.backup.NamespaceBackup;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class BackupsRepository implements PanacheRepositoryBase<NamespaceBackup, UUID> {

    private static final Set<NamespaceBackup.Status> STATUSES_OF_NOT_DELETABLE_NAMESPACE_BACKUPS = Set.of(
        NamespaceBackup.Status.RESTORING, NamespaceBackup.Status.PROCEEDING
    );

    public List<NamespaceBackup> findByNamespace(String namespace) {
        return list("namespace", namespace);
    }

    public List<UUID> findAllNotDeletableBackupIdsByNamespaces(Set<String> namespaces) {
        return find("select id FROM NamespaceBackup WHERE namespace in ?1 AND status in ?2", namespaces, STATUSES_OF_NOT_DELETABLE_NAMESPACE_BACKUPS)
            .project(UUID.class).list();
    }

    public List<NamespaceBackup> findByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit) {
        return find("namespace in ?1", namespaces).range(offset, offset + limit - 1).list();
    }

    public List<NamespaceBackup> findDeletableByNamespacesWithOffsetBasedPagination(Set<String> namespaces, int offset, int limit) {
        return find("namespace in ?1 AND status not in ?2", namespaces, STATUSES_OF_NOT_DELETABLE_NAMESPACE_BACKUPS)
            .range(offset, offset + limit - 1).list();
    }
}
