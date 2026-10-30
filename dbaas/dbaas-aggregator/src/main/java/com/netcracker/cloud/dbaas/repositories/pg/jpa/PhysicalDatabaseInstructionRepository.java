package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.util.UUID;

@Transactional
@ApplicationScoped
public class PhysicalDatabaseInstructionRepository implements PanacheRepositoryBase<PhysicalDatabaseInstruction, UUID> {
    public PhysicalDatabaseInstruction findByPhysicalDatabaseId(String physicalDatabaseId) {
        return find("physicalDatabaseId", physicalDatabaseId).firstResult();
    }

    public PhysicalDatabaseInstruction save(PhysicalDatabaseInstruction instruction) {
        EntityManager entityManager = getEntityManager();
        return entityManager.merge(instruction);
    }
}
