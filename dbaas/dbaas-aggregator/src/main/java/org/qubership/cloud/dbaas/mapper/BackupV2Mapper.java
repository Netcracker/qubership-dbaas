package org.qubership.cloud.dbaas.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.qubership.cloud.dbaas.dto.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.backupV2.*;
import org.qubership.cloud.dbaas.entity.pg.backupV2.BackupStatus;


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

    LogicalBackupDto toLogicalBackupDto(LogicalBackup logicalBackup);

    ExternalDatabaseStrategy toExternalDatabaseStrategy(ExternalDatabaseStrategyDto dto);

}
