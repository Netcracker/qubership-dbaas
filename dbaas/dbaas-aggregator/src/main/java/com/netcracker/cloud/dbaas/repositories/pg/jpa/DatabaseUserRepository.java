package org.qubership.cloud.dbaas.repositories.pg.jpa;

import org.qubership.cloud.dbaas.entity.pg.DatabaseUser;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DatabaseUserRepository implements PanacheRepositoryBase<DatabaseUser, UUID> {

    public List<DatabaseUser> findByLogicalDatabaseId(UUID logicalDatabaseId) {
        return list("database.id", logicalDatabaseId);
    }

}
