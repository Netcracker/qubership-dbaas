package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Backup;
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
        return getEntityManager()
                .createNativeQuery(
                        "select * from v2_backup " +
                                "where status = 'IN_PROGRESS'",
                        Backup.class
                )
                .getResultList();
    }
}
