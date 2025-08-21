package org.qubership.cloud.dbaas.entity.pg.backupV2;

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
