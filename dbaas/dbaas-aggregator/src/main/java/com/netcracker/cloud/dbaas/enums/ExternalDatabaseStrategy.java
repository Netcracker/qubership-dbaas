package com.netcracker.cloud.dbaas.enums;

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
