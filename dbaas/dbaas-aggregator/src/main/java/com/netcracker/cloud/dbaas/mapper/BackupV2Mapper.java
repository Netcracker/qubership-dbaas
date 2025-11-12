package com.netcracker.cloud.dbaas.mapper;

import com.netcracker.cloud.dbaas.dto.Source;
import com.netcracker.cloud.dbaas.dto.backupV2.*;
import com.netcracker.cloud.dbaas.entity.pg.backupV2.*;
import com.netcracker.cloud.dbaas.enums.BackupTaskStatus;
import com.netcracker.cloud.dbaas.enums.RestoreTaskStatus;
import com.netcracker.cloud.dbaas.exceptions.IllegalResourceStateException;
import com.netcracker.cloud.dbaas.exceptions.UnprocessableEntityException;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;
import java.util.function.Function;


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

    RestoreDatabaseResponse toRestoreDatabaseResponse(RestoreDatabase restoreDatabase);

    List<LogicalRestoreResponse> toLogicalRestoreResponse(List<LogicalRestore> logicalRestores);

    @Mapping(target = "restoreName", source = "name")
    @Mapping(target = "backupName", source = "backup.name")
    RestoreResponse toRestoreResponse(Restore restore);

    RestoreStatusResponse toRestoreStatusResponse(Restore restore);

    Filter toFilter(FilterEntity entity);

    FilterEntity toFilterEntity(Filter dto);

    FilterCriteria toFilterCriteria(FilterCriteriaEntity entity);

    Restore.MappingEntity toMappingEntity(com.netcracker.cloud.dbaas.dto.backupV2.Mapping mapping);

    FilterCriteriaEntity toFilterCriteriaEntity(FilterCriteria dto);

    default BackupTaskStatus toBackupTaskStatus(String status) {
        return mapStatus(status, BackupTaskStatus::valueOf);
    }

    default RestoreTaskStatus toRestoreTaskStatus(String status) {
        return mapStatus(status, RestoreTaskStatus::valueOf);
    }

    private static <T extends Enum<T>, R extends Enum<R>> R mapStatus(
            String status,
            Function<String, R> resultStatusGetter) {
        if (status == null) {
            throw new IllegalResourceStateException(
                    "null status returned from adapter",
                    Source.builder().build());
        }

        return switch (status) {
            case "notStarted" -> resultStatusGetter.apply("NOT_STARTED");
            case "inProgress" -> resultStatusGetter.apply("IN_PROGRESS");
            case "completed" -> resultStatusGetter.apply("COMPLETED");
            case "failed" -> resultStatusGetter.apply("FAILED");
            default -> throw new UnprocessableEntityException(
                    status,
                    "unknown status returned from adapter",
                    Source.builder().build());
        };
    }

    @Mapping(target = "id", ignore = true)
    RestoreExternalDatabase toRestoreExternalDatabase(BackupExternalDatabase backupExternalDatabase);

    List<RestoreExternalDatabase> toRestoreExternalDatabases(List<BackupExternalDatabase> backupExternalDatabases);
}
