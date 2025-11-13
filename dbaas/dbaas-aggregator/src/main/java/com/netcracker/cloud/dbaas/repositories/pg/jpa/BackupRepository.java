package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Backup;
import com.netcracker.cloud.dbaas.enums.BackupStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

@Transactional
@ApplicationScoped
public class BackupRepository implements PanacheRepositoryBase<Backup, String> {

    public Backup save(Backup backup) {
        EntityManager entityManager = getEntityManager();
        entityManager.merge(backup);
        return backup;
    }

    public List<Backup> findBackupsToAggregate() {
        return list("status in ?1", List.of(BackupStatus.NOT_STARTED, BackupStatus.IN_PROGRESS));
    }
}
