package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.cloud.dbaas.entity.pg.backupV2.Backup;

@ApplicationScoped
public class BackupRepository implements PanacheRepositoryBase<Backup, String> {

}
