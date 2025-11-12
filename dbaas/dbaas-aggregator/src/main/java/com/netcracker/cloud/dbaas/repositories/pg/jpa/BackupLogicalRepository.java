package com.netcracker.cloud.dbaas.repositories.pg.jpa;

import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupLogical;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional
public class BackupLogicalRepository implements PanacheRepositoryBase<BackupLogical, UUID> {

    public List<BackupLogical> getByBackupName(String backupName) {
        return find("backup.name", backupName).stream().toList();
    }

    public Optional<BackupLogical> getByAdapterId(String adapterId) {
        return find("adapterId", adapterId).firstResultOptional();
    }
}
