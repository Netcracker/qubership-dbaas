package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.qubership.cloud.dbaas.entity.pg.backupV2.BackupExternalDatabase;

import java.util.UUID;

@Transactional
@ApplicationScoped
public class BackupExternalDatabaseRepository implements PanacheRepositoryBase<BackupExternalDatabase, UUID> {

}
