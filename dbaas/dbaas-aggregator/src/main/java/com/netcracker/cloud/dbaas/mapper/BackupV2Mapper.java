package com.netcracker.cloud.dbaas.mapper;

import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.*;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;


@Mapper
public interface BackupV2Mapper {

    BackupStatusResponse toBackupStatusResponse(Backup backup);

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
        if (backup.getExternalDatabases() != null) {
            backup.getExternalDatabases().forEach(externalDatabase -> externalDatabase.setBackup(backup));
        }
    }

    BackupExternalDatabase toBackupExternalDatabase(BackupExternalDatabaseResponse response);

    List<BackupExternalDatabase> toBackupExternalDatabases(List<BackupExternalDatabaseResponse> responses);

    LogicalBackup toLogicalBackup(LogicalBackupResponse logicalBackupResponse);

    @Mapping(target = "dryRun", source = ".")
    @Mapping(target = "backupName", source = "name")
    BackupOperationResponse toBackupOperationResponse(Backup backup);

    @Mapping(target = "logicalBackupId", source = "backupDatabase.id")
    RestoreDatabaseResponse toLogicalRestoreDatabaseResponse(RestoreDatabase restoreDatabase);

    List<LogicalRestoreResponse> toLogicalRestoreResponseList(List<LogicalRestore> logicalRestores);

    @Mapping(target = "restoreName", source = "name")
    @Mapping(target = "backupName", source = "backup.name")
    RestoreResponse toRestoreResponse(Restore restore);

    RestoreStatusResponse toRestoreStatusResponse(Restore restore);

    Filter toFilter(FilterEntity entity);

    FilterEntity toFilterEntity(Filter dto);

    FilterCriteria toFilterCriteria(FilterCriteriaEntity entity);

    FilterCriteriaEntity toFilterCriteriaEntity(FilterCriteria dto);
}
