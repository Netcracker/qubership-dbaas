package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Backup;
import com.netcracker.cloud.dbaas.enums.BackupStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Set;

@Transactional
@ApplicationScoped
public class BackupRepository implements PanacheRepositoryBase<Backup, String> {

    public Backup save(Backup backup) {
        EntityManager entityManager = getEntityManager();
        return entityManager.merge(backup);
    }

    public List<Backup> findBackupsToTrack() {
        return list("status in ?1", List.of(BackupStatus.IN_PROGRESS));
    }

    public List<Backup> findAllBackupByBackupNames(Set<String> backupNames) {
        return list("name IN ?1", backupNames);
    }
}
