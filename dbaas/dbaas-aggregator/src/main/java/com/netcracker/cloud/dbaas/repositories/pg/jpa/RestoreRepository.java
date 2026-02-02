package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.Restore;
import com.netcracker.cloud.dbaas.enums.RestoreStatus;
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
        return entityManager.merge(restore);
    }

    public List<Restore> findRestoresToAggregate() {
        return list("status in ?1", List.of(RestoreStatus.IN_PROGRESS));
    }

    public long countNotCompletedRestores() {
        return count("status in ?1", List.of(RestoreStatus.NOT_STARTED, RestoreStatus.IN_PROGRESS));
    }
}
