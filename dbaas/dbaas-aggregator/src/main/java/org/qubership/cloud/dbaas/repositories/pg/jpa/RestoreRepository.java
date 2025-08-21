package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.qubership.cloud.dbaas.entity.pg.backupV2.Restore;

@Transactional
@ApplicationScoped
public class RestoreRepository implements PanacheRepositoryBase<Restore, String> {

    public Restore save(Restore restore) {
        EntityManager entityManager = getEntityManager();
        entityManager.merge(restore);
        return restore;
    }
}
