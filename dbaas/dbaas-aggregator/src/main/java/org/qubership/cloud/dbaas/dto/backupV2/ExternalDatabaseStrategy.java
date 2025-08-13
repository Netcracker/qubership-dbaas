package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Getter;

@Getter
public enum ExternalDatabaseStrategy {
    FAIL("fail"),
    SKIP("skip"),
    INCLUDE("include");

    private final String value;

    ExternalDatabaseStrategy(String value) {
        this.value = value;
    }

}
