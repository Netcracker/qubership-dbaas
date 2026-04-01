package com.netcracker.it.dbaas.entity.backup.v1;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BackupOperationResponse {
    private String backupName;
    private BackupResponse dryRun;
}
