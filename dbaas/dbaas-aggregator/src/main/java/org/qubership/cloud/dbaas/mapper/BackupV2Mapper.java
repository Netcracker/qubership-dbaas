package org.qubership.cloud.dbaas.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.cloud.dbaas.dto.backupV2.BackupMetadataResponse;
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

    default BackupMetadataResponse toBackupMetadataResponse(Backup backup) {
        BackupMetadataResponse response = new BackupMetadataResponse();
        response.setMetadata(toBackupResponse(backup));
        response.setControlSum("");
        return response;
    }

    LogicalBackupResponse toLogicalBackupResponse(LogicalBackup logicalBackup);
}
