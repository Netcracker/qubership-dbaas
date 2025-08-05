package org.qubership.cloud.dbaas.repositories.pg.jpa;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.cloud.dbaas.entity.pg.backupV2.LogicalBackup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class LogicalBackupRepository implements PanacheRepositoryBase<LogicalBackup, UUID> {

    public List<LogicalBackup> getByBackupName(String backupName){
        return find("backup.name", backupName).stream().toList();
    }

    public Optional<LogicalBackup> getByAdapterId(String adapterId){
        return find("adapterId", adapterId).firstResultOptional();
    }
}
