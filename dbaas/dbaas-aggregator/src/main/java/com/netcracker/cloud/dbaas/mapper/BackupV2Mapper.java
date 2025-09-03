package com.netcracker.cloud.dbaas.mapper;

import com.netcracker.cloud.dbaas.dto.backupV2.BackupResponse;
import com.netcracker.cloud.dbaas.dto.backupV2.BackupStatusResponse;
import com.netcracker.cloud.dbaas.dto.backupV2.LogicalBackupResponse;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.Backup;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.BackupStatus;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.LogicalBackup;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;


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
