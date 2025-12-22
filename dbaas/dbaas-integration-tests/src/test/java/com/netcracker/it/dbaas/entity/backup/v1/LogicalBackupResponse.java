package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
public class LogicalBackupResponse {

    String logicalBackupName;
    private String adapterId;
    private String type;

    private BackupTaskStatus status;

    private String errorMessage;

    private Instant creationTime;

    private Instant completionTime;

    private List<BackupDatabaseResponse> backupDatabases;
}
