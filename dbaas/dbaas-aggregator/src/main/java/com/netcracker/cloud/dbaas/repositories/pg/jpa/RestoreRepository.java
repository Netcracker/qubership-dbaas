package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Restore;
import com.netcracker.cloud.dbaas.enums.Status;
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
        return list("status in ?1", List.of(Status.PENDING, Status.IN_PROGRESS));
    }
}
