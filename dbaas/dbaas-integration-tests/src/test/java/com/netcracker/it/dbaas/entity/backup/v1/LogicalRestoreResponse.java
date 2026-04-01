package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogicalRestoreResponse {

    private UUID id;

    private String logicalRestoreName;

    private String adapterId;

    private String type;

    private List<RestoreDatabaseResponse> restoreDatabases;

    private RestoreTaskStatus status;

    private String errorMessage;

    private Instant creationTime;

    private Instant completionTime;
}
