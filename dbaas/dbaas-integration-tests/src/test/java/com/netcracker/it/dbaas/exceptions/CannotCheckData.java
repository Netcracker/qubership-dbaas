package com.netcracker.it.dbaas.exceptions;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CannotCheckData extends AssertionError {
    public CannotCheckData(Throwable error) {
        super(error);
        log.error("get error: {}", error.getMessage());
    }
}
