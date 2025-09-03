package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Restore;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

@Transactional
@ApplicationScoped
public class RestoreRepository implements PanacheRepositoryBase<Restore, String> {

    public Restore save(Restore restore) {
        EntityManager entityManager = getEntityManager();
        entityManager.merge(restore);
        return restore;
    }

    public List<Restore> findRestoresToAggregate() {
        return getEntityManager()
                .createNativeQuery(
                        "select * from v2_restore " +
                                "where status ->> 'status' in ('NOT_STARTED','IN_PROGRESS')",
                        Restore.class
                )
                .getResultList();
    }
}
