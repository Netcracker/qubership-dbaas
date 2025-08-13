package org.qubership.cloud.dbaas.dto.backupV2;


import lombok.Getter;

@Getter
public enum BackupStatus {
    PENDING("pending"), IN_PROGRESS("inProgress"), COMPLETED("completed"), FAILED("failed");

    private final String status;

    BackupStatus(String status) {
        this.status = status;
    }

}
