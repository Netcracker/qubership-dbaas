package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.PhysicalDatabaseInstruction;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class PhysicalDatabaseInstructionRepository implements PanacheRepositoryBase<PhysicalDatabaseInstruction, UUID> {
    public PhysicalDatabaseInstruction findByPhysicalDatabaseId(String physicalDatabaseId) {
        return find("physicalDatabaseId", physicalDatabaseId).firstResult();
    }
}
