package com.netcracker.it.dbaas.entity.backup.v1;

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
