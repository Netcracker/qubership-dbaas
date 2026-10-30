package com.netcracker.cloud.dbaas.exceptions;

public class DecryptException extends RuntimeException {
    public DecryptException(String message) {
        super(message);
    }

    public DecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
