package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.qubership.cloud.dbaas.entity.pg.backupV2.Backup;

@ApplicationScoped
@Transactional
public class BackupRepository implements PanacheRepositoryBase<Backup, String> {

    public Backup save(Backup backup) {
        EntityManager entityManager = getEntityManager();
        entityManager.merge(backup);
        return backup;
    }
}
