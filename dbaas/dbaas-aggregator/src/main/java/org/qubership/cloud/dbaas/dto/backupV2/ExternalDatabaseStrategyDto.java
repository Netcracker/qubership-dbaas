package org.qubership.cloud.dbaas.dto.backupV2;

import lombok.Getter;

@Getter
public enum ExternalDatabaseStrategyDto {
    FAIL("fail"),
    SKIP("skip"),
    INCLUDE("include");

    private final String value;

    ExternalDatabaseStrategyDto(String value) {
        this.value = value;
    }

}
