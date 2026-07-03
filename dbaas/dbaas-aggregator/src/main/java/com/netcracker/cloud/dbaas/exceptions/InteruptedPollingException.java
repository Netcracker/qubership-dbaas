package com.netcracker.cloud.dbaas.exceptions;
import com.netcracker.cloud.dbaas.logging.StructuredLog;

public class InteruptedPollingException extends RuntimeException {
    public InteruptedPollingException(String message) {
        super(message);
    }
}

