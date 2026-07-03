package com.netcracker.cloud.dbaas.enums;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

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
