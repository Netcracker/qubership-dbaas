package com.netcracker.cloud.dbaas.dto.backupV2;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

import lombok.Getter;

@Getter
public enum DatabaseKind {
    CONFIGURATION("configuration"),
    TRANSACTIONAL("transactional");

    private final String value;

    DatabaseKind(String value) {
        this.value = value;
    }

}
