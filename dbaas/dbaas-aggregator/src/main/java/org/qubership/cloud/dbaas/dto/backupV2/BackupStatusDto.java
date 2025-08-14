package org.qubership.cloud.dbaas.dto.backupV2;


import lombok.Getter;

@Getter
public enum BackupStatusDto {
    NOT_STARTED("notStarted"), PENDING("pending"), IN_PROGRESS("inProgress"), COMPLETED("completed"), FAILED("failed");

    private final String status;

    BackupStatusDto(String status) {
        this.status = status;
    }

}
