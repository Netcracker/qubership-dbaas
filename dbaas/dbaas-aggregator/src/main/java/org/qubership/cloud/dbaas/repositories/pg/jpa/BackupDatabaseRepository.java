package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.cloud.dbaas.entity.pg.backupV2.BackupDatabase;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class BackupDatabaseRepository implements PanacheRepositoryBase<BackupDatabase, UUID> {
}
