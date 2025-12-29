package com.netcracker.it.dbaas.entity.backup.v1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class BackupResponse {

    @NotBlank
    private String backupName;

    @NotBlank
    private String storageName;

    @NotBlank
    private String blobPath;

    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy;

    @NotNull
    private boolean ignoreNotBackupableDatabases;

    private FilterCriteria filterCriteria;

    private BackupStatus status;
    private Integer total;
    private Integer completed;
    private Long size;
    private String errorMessage;

    private List<LogicalBackupResponse> logicalBackups;

    private List<BackupExternalDatabaseResponse> externalDatabases;

    @JsonIgnore
    private String digest;
}
