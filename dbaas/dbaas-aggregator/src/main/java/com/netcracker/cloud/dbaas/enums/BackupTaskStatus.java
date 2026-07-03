package com.netcracker.cloud.dbaas.enums;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public enum BackupTaskStatus {
    NOT_STARTED, IN_PROGRESS, FAILED, RETRYABLE_FAIL, COMPLETED
}
