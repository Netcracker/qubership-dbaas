package com.netcracker.it.dbaas.entity.backup.v1;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RestoreResponse {
    private String restoreName;
    private String backupName;
    private String storageName;
    private String blobPath;
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy;
    private FilterCriteria filterCriteria;
    private Mapping mapping;
    private RestoreStatus status;
    private Integer total;
    private Integer completed;
    private String errorMessage;
    private Long duration;
    private int attemptCount;
    private List<LogicalRestoreResponse> logicalRestores;
    private List<RestoreExternalDatabaseResponse> externalDatabases;
}
