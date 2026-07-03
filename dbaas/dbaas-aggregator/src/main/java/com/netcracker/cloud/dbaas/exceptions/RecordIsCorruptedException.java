package com.netcracker.cloud.dbaas.exceptions;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public class RecordIsCorruptedException extends RuntimeException {
    public RecordIsCorruptedException(String message) {
        super(message);
    }
}
