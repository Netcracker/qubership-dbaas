package com.netcracker.it.dbaas.entity.backup.v1;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BackupRequest {

    @NotBlank
    private String backupName;

    @NotBlank
    private String storageName;

    @NotBlank
    private String blobPath;

    @Valid
    @NotNull(groups = {BackupGroup.class})
    private FilterCriteria filterCriteria;
    @NotNull
    private ExternalDatabaseStrategy externalDatabaseStrategy = ExternalDatabaseStrategy.FAIL;

    @NotNull
    private Boolean ignoreNotBackupableDatabases = false;
}
