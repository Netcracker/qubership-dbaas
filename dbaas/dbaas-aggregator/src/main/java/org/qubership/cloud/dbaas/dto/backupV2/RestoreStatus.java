package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Getter;

@Getter
public enum RestoreStatus {
    PENDING("pending"), IN_PROGRESS("inProgress"), COMPLETED("completed"), FAILED("failed");

    private final String status;

    RestoreStatus(String status) {
        this.status = status;
    }
}
