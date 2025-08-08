package org.qubership.cloud.dbaas.exceptions;

public class InteruptedPollingException extends RuntimeException {
    public InteruptedPollingException(String message) {
        super(message);
    }
}

