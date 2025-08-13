package com.netcracker.cloud.dbaas.exceptions;

public class RecordIsCorruptedException extends RuntimeException {
    public RecordIsCorruptedException(String message) {
        super(message);
    }
}
