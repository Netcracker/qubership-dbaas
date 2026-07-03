package com.netcracker.cloud.dbaas.entity.dto.backupV2;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public record AdapterBackupKey(String adapterId, String logicalBackupName) {
}
