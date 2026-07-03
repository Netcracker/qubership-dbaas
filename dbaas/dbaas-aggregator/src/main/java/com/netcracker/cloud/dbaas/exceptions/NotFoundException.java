package com.netcracker.cloud.dbaas.exceptions;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
