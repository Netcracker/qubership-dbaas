package org.qubership.cloud.dbaas.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.qubership.cloud.dbaas.dto.backupV2.BackupResponse;
import org.qubership.cloud.dbaas.dto.backupV2.BackupStatusResponse;
import org.qubership.cloud.dbaas.dto.backupV2.LogicalBackupResponse;
import org.qubership.cloud.dbaas.entity.pg.backupV2.Backup;
import org.qubership.cloud.dbaas.entity.pg.backupV2.BackupStatus;
import org.qubership.cloud.dbaas.entity.pg.backupV2.LogicalBackup;


@Mapper
public interface BackupV2Mapper {

    BackupStatusResponse toBackupStatusResponse(BackupStatus backupStatus);

    @Mapping(source = "name", target = "backupName")
    BackupResponse toBackupResponse(Backup backup);

    LogicalBackupResponse toLogicalBackupResponse(LogicalBackup logicalBackup);

    @Mapping(source = "backupName", target = "name")
    Backup toBackup(BackupResponse backupResponse);

    @AfterMapping
    default void setRelations(@MappingTarget Backup backup) {
        if (backup.getLogicalBackups() != null) {
            backup.getLogicalBackups().forEach(lb -> {
                lb.setBackup(backup);
                if (lb.getBackupDatabases() != null) {
                    lb.getBackupDatabases().forEach(db -> db.setLogicalBackup(lb));
                }
            });
        }
    }

    LogicalBackup toLogicalBackup(LogicalBackupResponse logicalBackupResponse);
}
