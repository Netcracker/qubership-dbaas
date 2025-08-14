package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.qubership.cloud.dbaas.entity.pg.backupV2.BackupDatabase;

import java.util.UUID;

@ApplicationScoped
@Transactional
public class BackupDatabaseRepository implements PanacheRepositoryBase<BackupDatabase, UUID> {
}
